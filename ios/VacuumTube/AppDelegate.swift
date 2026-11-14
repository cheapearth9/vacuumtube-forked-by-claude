import UIKit
import AVFoundation

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    /// one web view for the whole app: it lives in the phone's window, or in the external display's window when there is one
    static let host = TVHost()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // sound keeps playing with the silent switch on, and when the phone screen is off
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
        try? AVAudioSession.sharedInstance().setActive(true)

        // the tv picture is on another screen: the phone must not fall asleep and stop it
        application.isIdleTimerDisabled = true

        Self.host.start()
        return true
    }

    // the scene's role (phone screen or external display) picks which entry of Info.plist is used; the delegate looks at the role again
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        return UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }
}
