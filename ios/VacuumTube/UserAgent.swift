import Foundation

/// The identity VacuumTube presents to YouTube, in Cobalt's format (the engine the official TV apps run on).
/// Mirrors src/ua.js and the android app: leanback only lets a TV/console user agent in, and picks its UI quality from it.
enum UserAgent {
    private static let cobalt = "27.lts.3.1040976-gold"
    private static let v8 = "13.8.258.54"
    private static let starboard = 18
    private static let brand = "VacuumTube"

    static func build(appId: String, version: String) -> String {
        let year = Calendar.current.component(.year, from: Date())

        return "Mozilla/5.0 (X11; Linux aarch64) Cobalt/\(cobalt) (unlike Gecko) v8/\(v8)-jit gles Starboard/\(starboard)"
            + ", \(brand)_DESKTOP_arm64_\(year)/\(version) (\(brand), Linux) \(appId)/\(version)"
    }
}
