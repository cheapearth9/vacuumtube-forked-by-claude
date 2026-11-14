import UIKit

/// the phone or iPad's own screen
final class PhoneSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = RemoteViewController(host: AppDelegate.host)
        window.makeKeyAndVisible()
        self.window = window
    }
}

/// an HDMI or AirPlay display: as soon as one connects, the TV interface moves onto it (no toggle), and back when it goes
final class ExternalSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let controller = UIViewController()
        controller.view.backgroundColor = .black

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.isHidden = false
        self.window = window

        AppDelegate.host.attachExternal(window: window, container: controller.view)
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        if let window = window { AppDelegate.host.detachExternal(window: window) }
        window = nil
    }
}
