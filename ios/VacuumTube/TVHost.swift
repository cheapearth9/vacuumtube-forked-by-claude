import UIKit
import WebKit

/// Owns the web view that shows youtube.com/tv, and decides which screen it is on: an external display (HDMI or AirPlay) as
/// soon as there is one, otherwise the phone itself.
final class TVHost: NSObject {
    static let tvURL = URL(string: "https://www.youtube.com/tv")!

    let webView: WKWebView
    let config = Config()
    private(set) var externalWindow: UIWindow?

    /// called when the web view moves between the phone and an external display
    var onDisplayChange: (() -> Void)?
    /// where the web view sits when no external display is connected
    weak var phoneContainer: UIView?

    private let session: URLSession
    private let userAgent: String
    private let version: String
    private let bundleScript: String
    private var retryWork: DispatchWorkItem?
    private var injecting = false

    override init() {
        version = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
        userAgent = UserAgent.build(appId: Bundle.main.bundleIdentifier ?? "io.github.cheapearth9.vacuumtube", version: version)

        if let url = Bundle.main.url(forResource: "vacuumtube", withExtension: "js"),
           let text = try? String(contentsOf: url, encoding: .utf8) {
            // the bundle goes inside a <script> tag, so it must not contain a closing tag
            bundleScript = text.replacingOccurrences(of: "</script", with: "<\\/script")
        } else {
            bundleScript = ""
        }

        let sessionConfig = URLSessionConfiguration.ephemeral
        sessionConfig.httpShouldSetCookies = false // cookies are handed over by hand, to and from the web view's own store
        sessionConfig.httpCookieStorage = nil
        sessionConfig.timeoutIntervalForRequest = 20
        session = URLSession(configuration: sessionConfig)

        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.allowsAirPlayForMediaPlayback = false
        configuration.allowsPictureInPictureMediaPlayback = false

        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.customUserAgent = userAgent
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isUserInteractionEnabled = true // touch on the phone's own screen shows upstream's on-screen arrows / back / ok (touch-support.js)
        if #available(iOS 16.4, *) { webView.isInspectable = true }

        super.init()

        webView.navigationDelegate = self
        webView.uiDelegate = self
        configuration.userContentController.add(WeakScriptHandler(self), name: "vt")
    }

    func start() {
        // what ios offers for external displays, shown by the on-screen diagnostics when "Debug Info" is on
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(displayEvent(_:)), name: UIScene.willConnectNotification, object: nil)
        center.addObserver(self, selector: #selector(displayEvent(_:)), name: UIScene.didDisconnectNotification, object: nil)
        center.addObserver(self, selector: #selector(displayEvent(_:)), name: UIScreen.didConnectNotification, object: nil)
        center.addObserver(self, selector: #selector(displayEvent(_:)), name: UIScreen.didDisconnectNotification, object: nil)
        loadTV()
    }

    private var displayLog: [String] = []

    @objc private func displayEvent(_ note: Notification) {
        var detail = note.name.rawValue.replacingOccurrences(of: "Notification", with: "")
        if let scene = note.object as? UIScene { detail += " " + scene.session.role.rawValue }
        if let screen = note.object as? UIScreen { detail += " \(Int(screen.bounds.width))x\(Int(screen.bounds.height))" }
        report(detail)
    }

    /// writes a line about screens / scenes into the page's diagnostics (visible only with Debug Info on)
    func report(_ line: String) {
        let screens = UIScreen.screens.map { "\(Int($0.bounds.width))x\(Int($0.bounds.height))" + ($0.mirrored != nil ? " mirrored" : "") }
        let scenes = UIApplication.shared.connectedScenes.map { $0.session.role.rawValue.replacingOccurrences(of: "UIWindowSceneSessionRole", with: "") }
        let text = "display: \(line) | screens: \(screens.joined(separator: ", ")) | scenes: \(scenes.joined(separator: ", ")) | iOS \(UIDevice.current.systemVersion), \(UIDevice.current.model)"
        displayLog.append(text)
        webView.evaluateJavaScript("window.__vtDiag && window.__vtDiag(\(Self.literal(text)))", completionHandler: nil)
    }

    // MARK: external display

    func attachExternal(window: UIWindow, container: UIView) {
        externalWindow = window
        place(webView, in: container)
        webView.isUserInteractionEnabled = false // on the tv screen nobody can touch it; the phone becomes the remote
        onDisplayChange?()
    }

    func detachExternal(window: UIWindow) {
        guard externalWindow === window else { return }
        externalWindow = nil

        if let container = phoneContainer { place(webView, in: container) }
        webView.isUserInteractionEnabled = true
        onDisplayChange?()
    }

    func showOnPhoneIfNeeded() {
        if externalWindow == nil, let container = phoneContainer, webView.superview !== container { place(webView, in: container) }
    }

    private func place(_ view: WKWebView, in container: UIView) {
        view.removeFromSuperview()
        view.frame = container.bounds
        view.autoresizingMask = [ .flexibleWidth, .flexibleHeight ]
        container.addSubview(view)
    }

    // MARK: keys

    /// hands a key press from the keyboard or the on-screen remote to the page
    func sendKey(down: Bool, key: String, code: String, keyCode: Int, ctrl: Bool = false, shift: Bool = false, alt: Bool = false) {
        let script = "window.__vtKey && window.__vtKey(\(down ? "'keydown'" : "'keyup'"), \(Self.literal(key)), \(Self.literal(code)), \(keyCode), \(ctrl), \(shift), \(alt), false)"
        webView.evaluateJavaScript(script, completionHandler: nil)
    }

    func toggleSettings() {
        webView.evaluateJavaScript("window.vtToggleSettingsOverlay && window.vtToggleSettingsOverlay()", completionHandler: nil)
    }

    private static func literal(_ text: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [ text ]), let json = String(data: data, encoding: .utf8) else { return "''" }
        return String(json.dropFirst().dropLast()) // ["x"] -> "x"
    }

    // MARK: loading

    /// Fetches youtube.com/tv itself, drops the content-security policy and puts VacuumTube's script first in <head>.
    /// That is what makes the scripts run before YouTube's own (the same trick as the android app), and the policy
    /// would otherwise block them (trusted types), sponsorblock's requests and the settings menu's styles.
    func loadTV() {
        retryWork?.cancel()

        var request = URLRequest(url: Self.tvURL)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(Locale.preferredLanguages.prefix(3).joined(separator: ","), forHTTPHeaderField: "Accept-Language")

        let store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { [weak self] cookies in
            guard let self = self else { return }

            let matching = cookies.filter { cookie in
                let domain = cookie.domain.hasPrefix(".") ? String(cookie.domain.dropFirst()) : cookie.domain
                guard let host = Self.tvURL.host, host == domain || host.hasSuffix("." + domain) else { return false }
                return cookie.path == "/" || "/tv".hasPrefix(cookie.path)
            }

            for (field, value) in HTTPCookie.requestHeaderFields(with: matching) { request.setValue(value, forHTTPHeaderField: field) }

            self.session.dataTask(with: request) { [weak self] data, response, error in
                DispatchQueue.main.async { self?.received(data: data, response: response, error: error) }
            }.resume()
        }
    }

    private func received(data: Data?, response: URLResponse?, error: Error?) {
        guard let data = data, let http = response as? HTTPURLResponse, http.statusCode == 200,
              var html = String(data: data, encoding: .utf8) else {
            showOffline()
            return
        }

        // keep the login alive: cookies the server sets go into the web view's store
        if let fields = http.allHeaderFields as? [String: String] {
            for cookie in HTTPCookie.cookies(withResponseHeaderFields: fields, for: http.url ?? Self.tvURL) {
                webView.configuration.websiteDataStore.httpCookieStore.setCookie(cookie)
            }
        }

        // youtube sent us somewhere else (a consent page, say): show that as it is
        if let finalURL = http.url, finalURL.host != Self.tvURL.host || !finalURL.path.hasPrefix("/tv") {
            webView.load(URLRequest(url: finalURL))
            return
        }

        html = html.replacingOccurrences(of: "<meta[^>]*http-equiv=[\"']?content-security-policy[^>]*>", with: "", options: [ .regularExpression, .caseInsensitive ])
        html = injecting(into: html)

        injecting = true
        webView.loadHTMLString(html, baseURL: Self.tvURL)
    }

    private func injecting(into html: String) -> String {
        let native: [String: Any] = [
            "config": config.json,
            "app": [
                "version": version,
                "arch": "arm64",
                "platform": "iOS",
                "device": UIDevice.current.model
            ]
        ]

        let nativeJSON = (try? JSONSerialization.data(withJSONObject: native)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let tag = "<script>window.__vtNative=\(nativeJSON.replacingOccurrences(of: "</", with: "<\\/"));\n\(bundleScript)</script>"

        if let range = html.range(of: "<head[^>]*>", options: [ .regularExpression, .caseInsensitive ]) {
            var result = html
            result.insert(contentsOf: tag, at: range.upperBound)
            return result
        }

        return tag + html
    }

    private func showOffline() {
        let page = "<body style='background:#000;color:#fff;font:28px -apple-system,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>Can't reach YouTube. Trying again…</body>"
        injecting = true
        webView.loadHTMLString(page, baseURL: nil)

        let work = DispatchWorkItem { [weak self] in self?.loadTV() }
        retryWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
    }
}

// MARK: - web view delegates

extension TVHost: WKNavigationDelegate, WKUIDelegate {
    private func isGoogleHost(_ host: String) -> Bool {
        let domains = [ "youtube.com", "google.com", "googlevideo.com", "gstatic.com", "ytimg.com", "ggpht.com", "youtube-nocookie.com" ]
        return domains.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    private func withoutFragment(_ url: URL?) -> String {
        guard let url = url, var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return "" }
        parts.fragment = nil
        return parts.string ?? ""
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url, navigationAction.targetFrame?.isMainFrame ?? true else {
            decisionHandler(.allow)
            return
        }

        if injecting { // our own loadHTMLString
            decisionHandler(.allow)
            return
        }

        if url.scheme == "about" {
            decisionHandler(.allow)
            return
        }

        // moving around inside the page (leanback's #/ routes) is the same document
        if navigationAction.navigationType != .reload, withoutFragment(url) == withoutFragment(webView.url) {
            decisionHandler(.allow)
            return
        }

        if url.host == Self.tvURL.host, url.path.hasPrefix("/tv") { // a real (re)load of the tv page: go through the injection again
            decisionHandler(.cancel)
            loadTV()
            return
        }

        if let host = url.host, isGoogleHost(host) {
            decisionHandler(.allow)
        } else {
            decisionHandler(.cancel)
            openExternal(url)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        injecting = false

        // the page's diagnostics aren't up for a moment: report the current state then
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in self?.report("status") }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        injecting = false
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        injecting = false
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        loadTV() // ios killed the page to free memory
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        return nil // no popups
    }

    @available(iOS 15.0, *)
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(isGoogleHost(origin.host) ? .grant : .deny) // voice search
    }

    fileprivate func openExternal(_ url: URL) {
        guard url.scheme == "https" else { return }
        UIApplication.shared.open(url)
    }
}

// MARK: - messages from the page

extension TVHost: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let action = body["a"] as? String else { return }

        switch action {
        case "setConfig":
            if let text = body["config"] as? String { config.replace(withJSON: text) }
        case "reload":
            loadTV()
        case "open":
            if let text = body["url"] as? String, let url = URL(string: text) { openExternal(url) }
        default:
            break
        }
    }
}

/// WKUserContentController keeps a strong reference to its handlers; this avoids a retain cycle with the host
private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    private weak var target: WKScriptMessageHandler?

    init(_ target: WKScriptMessageHandler) {
        self.target = target
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}
