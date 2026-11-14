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
            // an HDMI or AirPlay display: hand the scene over. The TV interface goes onto it by itself while "AirPlay / HDMI
            // Display" is on in the settings (the default), and back to the phone when it is switched off or the display goes.
            AppDelegate.host.externalSceneConnected(windowScene)
        }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        guard let windowScene = scene as? UIWindowScene, windowScene.session.role != .windowApplication else { return }
        AppDelegate.host.externalSceneDisconnected(windowScene)
    }
}
