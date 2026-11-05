package io.github.cheapearth9.vacuumtube;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.Locale;

/** Looks at what the device can do, so that weak TVs and boxes get sensible defaults. */
final class Devices {
    static final String VP9 = "video/x-vnd.on2.vp9";
    static final String AV1 = "video/av01";

    private Devices() {}

    /** True if the device has a hardware (not software) video decoder for the mime type. */
    static boolean hasHardwareDecoder(String mime) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;

                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime) && isHardware(info)) return true;
                }
            }
        } catch (Exception ignored) {
            // some boxes have broken codec lists, treat that as "no"
        }

        return false;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();

        String name = info.getName().toLowerCase(Locale.US);
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.") || name.endsWith(".sw"));
    }

    /** Old or small devices: they get VacuumTube's "low memory mode", which makes youtube's interface lighter. */
    static boolean isLowEnd(Context context) {
        if (Build.VERSION.SDK_INT < 24) return true;

        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        if (manager.isLowRamDevice()) return true;

        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        return memory.totalMem > 0 && memory.totalMem < 1_600_000_000L; // 1 GB class
    }
}
