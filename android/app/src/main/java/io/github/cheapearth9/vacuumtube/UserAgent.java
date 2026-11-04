package io.github.cheapearth9.vacuumtube;

import android.os.Build;

import java.util.Calendar;

/**
 * Builds the identity VacuumTube presents to YouTube, in the format of Cobalt (the engine the official TV apps run on).
 * Mirrors src/ua.js: leanback only lets you in with a TV/console app user agent, and picks its UI quality based on it.
 */
final class UserAgent {
    private static final String COBALT = "27.lts.3.1040976-gold";
    private static final String V8 = "13.8.258.54";
    private static final int STARBOARD = 18;
    private static final String BRAND = "VacuumTube";

    private UserAgent() {}

    /** "arm64", "arm", "x86_64" or "x86" */
    static String arch() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm";
        if (abi.startsWith("arm64")) return "arm64";
        if (abi.startsWith("armeabi")) return "arm";
        if (abi.equals("x86_64")) return "x86_64";
        return "x86";
    }

    static String build(String appId, String version) {
        String os;
        String chipset;

        switch (arch()) {
            case "arm64":
                os = "X11; Linux aarch64";
                chipset = "arm64";
                break;
            case "arm":
                os = "X11; Linux armv7l";
                chipset = "arm";
                break;
            case "x86_64":
                os = "X11; Linux x86_64";
                chipset = "x8664"; // cobalt strips everything but alphanumerics
                break;
            default:
                os = "X11; Linux i686";
                chipset = "x86";
                break;
        }

        int year = Calendar.getInstance().get(Calendar.YEAR);

        return "Mozilla/5.0 (" + os + ") Cobalt/" + COBALT + " (unlike Gecko) v8/" + V8 + "-jit gles Starboard/" + STARBOARD
                + ", " + BRAND + "_DESKTOP_" + chipset + "_" + year + "/" + version
                + " (" + BRAND + ", Linux) " + appId + "/" + version;
    }
}
