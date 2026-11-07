package io.github.cheapearth9.vacuumtube;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.UUID;

/**
 * What the electron main process used to do for the preload modules (config storage, reload, exit...).
 * Exposed to the page as window.AndroidBridge; the JS side is android/js/shims/electron.js.
 * Methods are called from a WebView background thread, so anything touching views goes through the UI thread.
 */
final class Bridge {
    private static final String TAG = "VacuumTube";
    private static final String PREFS = "vacuumtube";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_DEFAULTS_VERSION = "defaults_version";
    private static final String GENERATE = "__GENERATE__";

    private final MainActivity activity;
    private final int webViewMajor; // 0 when unknown
    private final SharedPreferences prefs;
    private final JSONObject config;

    Bridge(MainActivity activity, int webViewMajor) {
        this.activity = activity;
        this.webViewMajor = webViewMajor;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.config = loadConfig();
    }

    private JSONObject loadConfig() {
        JSONObject merged = readDefaults();

        String stored = prefs.getString(KEY_CONFIG, null);
        if (stored != null) {
            try {
                merge(merged, new JSONObject(stored));
            } catch (JSONException e) {
            }
        }

        try {
            int version = prefs.getInt(KEY_DEFAULTS_VERSION, 1);
            if (version < 7) {
                if (version == 2) merged.put("h264ify", false); // builds 8 and 9 turned it on for everyone, which also blocked 4k
                applyDeviceDefaults(merged);
                if (version < 7) merged.put("volume", 100); // earlier builds could leave the player quieter than the tv's volume
                prefs.edit().putString(KEY_CONFIG, merged.toString()).putInt(KEY_DEFAULTS_VERSION, 7).apply();
            }

            if (GENERATE.equals(merged.optString("sponsorblock_uuid"))) {
                merged.put("sponsorblock_uuid", UUID.randomUUID().toString()); // sponsorblock wants a stable per-user id
                prefs.edit().putString(KEY_CONFIG, merged.toString()).apply();
            }
        } catch (JSONException ignored) {
        }

        return merged;
    }

    /**
     * Devices that can't decode vp9/av1 in hardware fall back to software decoding, which stutters badly on a tv box.
     * Block just the codecs the device is bad at, and keep the rest (4k vp9 stays on devices that handle it).
     * Only turns things on, so it never overrides a choice made in the settings.
     */
    private void applyDeviceDefaults(JSONObject config) throws JSONException {
        boolean vp9 = Devices.hasHardwareDecoder(Devices.VP9);
        boolean av1 = Devices.hasHardwareDecoder(Devices.AV1);

        if (!config.optBoolean("h264ify", false)) {
            if (!vp9) {
                config.put("h264ify", true); // everything but h264
            } else if (!av1) {
                config.put("h264ify", true);
                config.put("h264ify_disable_webm", false);
                config.put("h264ify_disable_vp8", false);
                config.put("h264ify_disable_vp9", false);
                config.put("h264ify_disable_av1", true); // only av1
            }
        }

        if ((Devices.isLowEnd(activity) || (webViewMajor > 0 && webViewMajor < 90)) && !config.optBoolean("low_memory_mode", false)) {
            config.put("low_memory_mode", true);
        }

    }

    synchronized boolean flag(String key) {
        return config.optBoolean(key, false);
    }

    private JSONObject readDefaults() {
        try (InputStream in = activity.getAssets().open("config-defaults.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "could not read config-defaults.json", e);
            return new JSONObject();
        }
    }

    private static void merge(JSONObject into, JSONObject from) throws JSONException {
        Iterator<String> keys = from.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            into.put(key, from.get(key));
        }
    }

    @JavascriptInterface
    public synchronized String getConfig() {
        return config.toString();
    }

    @JavascriptInterface
    public synchronized String setConfig(String json) {
        try {
            merge(config, new JSONObject(json));
            prefs.edit().putString(KEY_CONFIG, config.toString()).apply();
        } catch (JSONException e) {
            activity.debugLog("ignoring invalid config update", e);
        }

        return config.toString();
    }

    @JavascriptInterface
    public String getAppInfo() {
        JSONObject info = new JSONObject();
        try {
            info.put("version", activity.versionName());
            info.put("arch", UserAgent.arch());
            info.put("sdk", Build.VERSION.SDK_INT);
        } catch (JSONException ignored) {
        }

        return info.toString();
    }

    @JavascriptInterface
    public void checkForUpdates() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.checkForUpdates(true);
            }
        });
    }

    /** True if the device can really do Widevine DRM. Without it, youtube must be told there is no DRM at all. */
    @JavascriptInterface
    public boolean hasWidevine() {
        try {
            return android.media.MediaDrm.isCryptoSchemeSupported(java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"));
        } catch (Throwable e) {
            return false;
        }
    }

    @JavascriptInterface
    public String getDeviceName() {
        return Build.MODEL != null ? Build.MODEL : "Android TV";
    }

    @JavascriptInterface
    public void reload() {
        activity.runOnUiThread(activity::reloadTv);
    }

    @JavascriptInterface
    public void exit() {
        activity.runOnUiThread(activity::finish);
    }

    @JavascriptInterface
    public boolean requestMicrophone() {
        boolean granted = activity.hasMicrophonePermission();
        if (!granted) activity.runOnUiThread(activity::askForMicrophone);
        return granted;
    }

    @JavascriptInterface
    public void openExternal(String url) {
        if (url == null || !url.startsWith("https://")) return;

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            activity.debugLog("nothing can open " + url, e); // a lot of tv boxes have no browser
        }
    }
}
