import Foundation

/// VacuumTube's settings: upstream's defaults (config-defaults.json, generated from src/config.js) with whatever was saved on top.
final class Config {
    private static let key = "vacuumtube-config"
    private(set) var values: [String: Any]

    init() {
        var merged: [String: Any] = [:]

        if let url = Bundle.main.url(forResource: "config-defaults", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let defaults = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            merged = defaults
        }

        if let saved = UserDefaults.standard.string(forKey: Config.key),
           let data = saved.data(using: .utf8),
           let overrides = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            merged.merge(overrides) { _, new in new }
        }

        if (merged["sponsorblock_uuid"] as? String).map({ $0.isEmpty || $0 == "__GENERATE__" }) ?? true {
            merged["sponsorblock_uuid"] = UUID().uuidString.lowercased()
        }

        if merged["airplay_mode"] == nil { merged["airplay_mode"] = true }

        values = merged
        save()
    }

    /// whether the TV interface should move to a connected HDMI / AirPlay display ("AirPlay / HDMI Display" in the settings)
    var airplayMode: Bool { (values["airplay_mode"] as? Bool) ?? true }

    var json: String {
        guard let data = try? JSONSerialization.data(withJSONObject: values),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }

    /// the page sends back its full, merged settings object whenever something changes
    func replace(withJSON text: String) {
        guard let data = text.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        values = object
        save()
    }

    private func save() {
        UserDefaults.standard.set(json, forKey: Config.key)
    }
}
