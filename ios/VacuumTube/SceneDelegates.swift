import UIKit

/// Both the phone / iPad screen and an HDMI or AirPlay display come here, told apart by the scene's role.
/// (Set up the same way as AirBrowser, whose external display works: one declared external role, one delegate.)
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        if session.role == .windowApplication {
            // the phone's own screen
            let window = UIWindow(windowScene: windowScene)
            window.rootViewController = RemoteViewController(host: AppDelegate.host)
            window.makeKeyAndVisible()
            self.window = window
        } else {
            // an external display: the TV interface moves onto it as soon as it connects (no toggle), and back when it goes
            let controller = UIViewController()
            controller.view.backgroundColor = .black

            let window = UIWindow(windowScene: windowScene)
            window.rootViewController = controller
            window.isHidden = false // not makeKeyAndVisible: the phone keeps keyboard focus
            self.window = window

            AppDelegate.host.attachExternal(window: window, container: controller.view)
        }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        guard let windowScene = scene as? UIWindowScene, windowScene.session.role != .windowApplication else { return }
        if let window = window { AppDelegate.host.detachExternal(window: window) }
        window = nil
    }
}
