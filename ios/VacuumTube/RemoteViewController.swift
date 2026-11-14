import UIKit

/// The phone's own screen. With an external display connected, the TV interface is on that display and this is a remote
/// (plus a keyboard receiver). Without one, the TV interface fills the phone and the page's own touch controls (arrows,
/// back and ok, from upstream's touch-support.js) appear when it is touched.
final class RemoteViewController: UIViewController {
    struct Key {
        let key: String
        let code: String
        let keyCode: Int
        var repeats = false
    }

    private static let up = Key(key: "ArrowUp", code: "ArrowUp", keyCode: 38, repeats: true)
    private static let down = Key(key: "ArrowDown", code: "ArrowDown", keyCode: 40, repeats: true)
    private static let left = Key(key: "ArrowLeft", code: "ArrowLeft", keyCode: 37, repeats: true)
    private static let right = Key(key: "ArrowRight", code: "ArrowRight", keyCode: 39, repeats: true)
    private static let enter = Key(key: "Enter", code: "Enter", keyCode: 13)
    private static let escape = Key(key: "Escape", code: "Escape", keyCode: 27)
    private static let playPause = Key(key: "MediaPlayPause", code: "MediaPlayPause", keyCode: 179)

    private let host: TVHost
    private let webContainer = UIView()
    private let panel = UIView()
    private let statusLabel = UILabel()

    private var remoteButtons: [UIButton: Key] = [:]
    private var held: [ObjectIdentifier: Key] = [:]
    private var repeatTimer: Timer?

    init(host: TVHost) {
        self.host = host
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var canBecomeFirstResponder: Bool { true }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }

    // MARK: layout

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        webContainer.frame = view.bounds
        webContainer.autoresizingMask = [ .flexibleWidth, .flexibleHeight ]
        webContainer.backgroundColor = .black
        view.addSubview(webContainer)

        buildPanel()
        view.addSubview(panel)

        host.phoneContainer = webContainer
        host.showOnPhoneIfNeeded()
        host.onDisplayChange = { [weak self] in self?.refresh() }

        // touching the page can make the web view grab the keyboard: take it back, so keys always arrive the same way
        let touch = UITapGestureRecognizer(target: self, action: #selector(touched))
        touch.cancelsTouchesInView = false
        view.addGestureRecognizer(touch)
        NotificationCenter.default.addObserver(self, selector: #selector(becomeReceiver), name: UIApplication.didBecomeActiveNotification, object: nil)
        refresh()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        becomeReceiver()
    }

    @objc private func becomeReceiver() {
        becomeFirstResponder()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        panel.frame = view.bounds
        layoutRemote()
    }

    // the original VacuumTube's touch controls (touch-support.js): round dark buttons, the four arrows in a row at the bottom
    // left, back and ok at the bottom right
    private var leftCluster: [UIButton] = []   // left, right, up, down (the same order as upstream)
    private var rightCluster: [UIButton] = []  // back, ok
    private let settingsKey = UIButton(type: .system)
    private let playKey = UIButton(type: .system)

    private func buildPanel() {
        panel.backgroundColor = .black
        panel.autoresizingMask = [ .flexibleWidth, .flexibleHeight ]

        statusLabel.textColor = UIColor(white: 1, alpha: 0.7)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.font = .preferredFont(forTextStyle: .body)
        panel.addSubview(statusLabel)

        leftCluster = [
            round(symbol: "chevron.left", Self.left),
            round(symbol: "chevron.right", Self.right),
            round(symbol: "chevron.up", Self.up),
            round(symbol: "chevron.down", Self.down)
        ]
        rightCluster = [
            round(text: "◦", size: 0.7, Self.escape),  // back
            round(text: "·", size: 1.2, Self.enter)    // ok
        ]

        for b in leftCluster + rightCluster { panel.addSubview(b) }

        // not in upstream's overlay, but the phone is the only remote there is
        for (b, symbol) in [ (settingsKey, "gearshape"), (playKey, "playpause.fill") ] {
            b.setImage(UIImage(systemName: symbol), for: .normal)
            b.tintColor = .white
            b.backgroundColor = UIColor(red: 0.153, green: 0.153, blue: 0.153, alpha: 0.9)
            panel.addSubview(b)
        }

        settingsKey.addTarget(self, action: #selector(openSettings), for: .touchUpInside)
        remoteButtons[playKey] = Self.playPause
        playKey.addTarget(self, action: #selector(buttonDown(_:)), for: .touchDown)
        playKey.addTarget(self, action: #selector(buttonUp(_:)), for: [ .touchUpInside, .touchUpOutside, .touchCancel ])
    }

    private func layoutRemote() {
        let bounds = panel.bounds
        let margin = max(bounds.height * 0.05, 16)
        let gap = max(bounds.width * 0.01, 8)

        // 10% of the width like upstream, as large as the screen lets six of them fit
        let fit = (bounds.width - margin * 2 - gap * 7) / 6
        let d = max(52, min(max(64, bounds.width * 0.1), fit))
        let bottom = bounds.height - margin - view.safeAreaInsets.bottom * 0.5

        for (i, b) in leftCluster.enumerated() {
            b.frame = CGRect(x: margin + CGFloat(i) * (d + gap), y: bottom - d, width: d, height: d)
            b.layer.cornerRadius = d / 2
        }

        for (i, b) in rightCluster.reversed().enumerated() { // ok is the right-most
            b.frame = CGRect(x: bounds.width - margin - d - CGFloat(i) * (d + gap), y: bottom - d, width: d, height: d)
            b.layer.cornerRadius = d / 2
        }

        let small = d * 0.6
        playKey.frame = CGRect(x: bounds.width - margin - small, y: margin + view.safeAreaInsets.top, width: small, height: small)
        settingsKey.frame = CGRect(x: playKey.frame.minX - gap - small, y: playKey.frame.minY, width: small, height: small)
        playKey.layer.cornerRadius = small / 2
        settingsKey.layer.cornerRadius = small / 2

        statusLabel.frame = CGRect(x: margin, y: margin + view.safeAreaInsets.top, width: max(0, settingsKey.frame.minX - margin * 2), height: small)
    }

    private func roundStyle(_ b: UIButton) {
        b.backgroundColor = UIColor(red: 0.153, green: 0.153, blue: 0.153, alpha: 0.9) // #272727
        b.tintColor = .white
        b.setTitleColor(.white, for: .normal)
    }

    private func round(symbol: String, _ key: Key) -> UIButton {
        let b = UIButton(type: .system)
        b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 30, weight: .bold)), for: .normal)
        roundStyle(b)
        wire(b, key)
        return b
    }

    private func round(text: String, size: CGFloat, _ key: Key) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(text, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 44 * size, weight: .bold)
        roundStyle(b)
        wire(b, key)
        return b
    }

    private func wire(_ b: UIButton, _ key: Key) {
        remoteButtons[b] = key
        b.addTarget(self, action: #selector(buttonDown(_:)), for: .touchDown)
        b.addTarget(self, action: #selector(buttonUp(_:)), for: [ .touchUpInside, .touchUpOutside, .touchCancel ])
    }

    // MARK: state

    private func refresh() {
        let external = host.externalWindow != nil
        statusLabel.text = "VacuumTube is on your TV display. A keyboard works too."
        panel.isHidden = !external // without a display, the page's own touch controls do the job
        view.setNeedsLayout()
    }

    @objc private func touched() {
        DispatchQueue.main.async { [weak self] in self?.becomeFirstResponder() }
    }

    // MARK: remote buttons

    @objc private func buttonDown(_ sender: UIButton) {
        guard let key = remoteButtons[sender] else { return }
        press(key, by: ObjectIdentifier(sender))
    }

    @objc private func buttonUp(_ sender: UIButton) {
        release(by: ObjectIdentifier(sender))
    }

    @objc private func openSettings() {
        host.toggleSettings()
    }

    // MARK: keyboard

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var unhandled = Set<UIPress>()

        for press in presses {
            guard let key = Self.key(for: press) else {
                unhandled.insert(press)
                continue
            }

            let flags = press.key?.modifierFlags ?? []
            self.press(key, by: ObjectIdentifier(press), ctrl: flags.contains(.control), shift: flags.contains(.shift), alt: flags.contains(.alternate))
        }

        if !unhandled.isEmpty { super.pressesBegan(unhandled, with: event) }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var unhandled = Set<UIPress>()

        for press in presses {
            if held[ObjectIdentifier(press)] != nil { release(by: ObjectIdentifier(press)) } else { unhandled.insert(press) }
        }

        if !unhandled.isEmpty { super.pressesEnded(unhandled, with: event) }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var unhandled = Set<UIPress>()

        for press in presses {
            if held[ObjectIdentifier(press)] != nil { release(by: ObjectIdentifier(press)) } else { unhandled.insert(press) }
        }

        if !unhandled.isEmpty { super.pressesCancelled(unhandled, with: event) }
    }

    /// maps a hardware keyboard key to what the page expects; nil leaves it to the system (cmd-tab and the like)
    private static func key(for press: UIPress) -> Key? {
        guard let key = press.key else { return nil }
        if key.modifierFlags.contains(.command) { return nil }

        switch key.keyCode {
        case .keyboardUpArrow: return up
        case .keyboardDownArrow: return down
        case .keyboardLeftArrow: return left
        case .keyboardRightArrow: return right
        case .keyboardReturnOrEnter, .keypadEnter: return enter
        case .keyboardEscape: return escape
        case .keyboardDeleteOrBackspace: return Key(key: "Backspace", code: "Backspace", keyCode: 8)
        case .keyboardDeleteForward: return Key(key: "Delete", code: "Delete", keyCode: 46)
        case .keyboardTab: return Key(key: "Tab", code: "Tab", keyCode: 9)
        case .keyboardSpacebar: return Key(key: " ", code: "Space", keyCode: 32)
        default: break
        }

        // letters, digits and punctuation, for the search box and shortcuts
        let text = key.charactersIgnoringModifiers
        guard text.count == 1, let scalar = text.unicodeScalars.first else { return nil }

        let upper = text.uppercased().unicodeScalars.first ?? scalar
        let code = (text.rangeOfCharacter(from: .letters) != nil) ? "Key" + text.uppercased()
            : (text.rangeOfCharacter(from: .decimalDigits) != nil ? "Digit" + text : "")
        return Key(key: key.characters.isEmpty ? text : key.characters, code: code, keyCode: Int(upper.value))
    }

    // MARK: sending, with key repeat for the arrows (a held key arrives only once)

    private var lastModifiers: (ctrl: Bool, shift: Bool, alt: Bool) = (false, false, false)

    private func press(_ key: Key, by id: ObjectIdentifier, ctrl: Bool = false, shift: Bool = false, alt: Bool = false) {
        held[id] = key
        lastModifiers = (ctrl, shift, alt)
        host.sendKey(down: true, key: key.key, code: key.code, keyCode: key.keyCode, ctrl: ctrl, shift: shift, alt: alt)

        if key.repeats {
            // a pause before the repeat starts, like a real keyboard
            repeatTimer?.invalidate()
            let timer = Timer(fire: Date().addingTimeInterval(0.4), interval: 0.11, repeats: true) { [weak self] _ in
                self?.host.sendKey(down: true, key: key.key, code: key.code, keyCode: key.keyCode)
            }
            RunLoop.main.add(timer, forMode: .common)
            repeatTimer = timer
        }
    }

    private func release(by id: ObjectIdentifier) {
        guard let key = held.removeValue(forKey: id) else { return }
        if key.repeats {
            repeatTimer?.invalidate()
            repeatTimer = nil
        }

        host.sendKey(down: false, key: key.key, code: key.code, keyCode: key.keyCode, ctrl: lastModifiers.ctrl, shift: lastModifiers.shift, alt: lastModifiers.alt)
    }
}
