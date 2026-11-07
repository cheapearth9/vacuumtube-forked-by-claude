package io.github.cheapearth9.vacuumtube;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Over-the-air updates. CI publishes update.json next to the APK in the "android-latest" release:
 *   { "versionCode": 12, "versionName": "2.0.2-tv", "apk": "https://github.com/<repo>/releases/download/android-latest/VacuumTube-AndroidTV.apk" }
 * If its versionCode is higher than the installed one, a dialog offers Update / Later / Skip this version.
 * "Update" downloads the APK and hands it to android's package installer (which asks for the final confirmation).
 */
final class Updater {
    private static final String TAG = "VacuumTubeUpdate";
    private static final String PREFS = "vacuumtube-updates";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_SKIPPED = "skipped_version";

    private final MainActivity activity;
    private final SharedPreferences prefs;
    private final String baseUrl = "https://github.com/" + BuildConfig.UPDATE_REPO + "/releases/download/";

    private boolean checking;
    private File pendingInstall; // downloaded, but android still has to let this app install apps

    Updater(MainActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Automatic check on every launch, silent unless there is something new (a version you skipped stays quiet). */
    void checkIfDue() {
        check(false);
    }

    /** @param manual true when the user asked (from the about page): ignores "skip this version" and reports the result */
    void check(final boolean manual) {
        synchronized (this) {
            if (checking) return;
            checking = true;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject info = fetchInfo();
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

                    final int latest = info.getInt("versionCode");
                    final String name = info.optString("versionName", String.valueOf(latest));
                    final String apk = info.getString("apk");

                    // only ever download from this project's own releases
                    if (!apk.startsWith(baseUrl)) throw new IllegalStateException("unexpected apk url " + apk);

                    final boolean newer = latest > activity.versionCode();
                    final boolean skipped = prefs.getInt(KEY_SKIPPED, 0) == latest;

                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing()) return;

                            if (newer && (manual || !skipped)) showOffer(name, latest, apk);
                            else if (manual) toast("VacuumTube is up to date");
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "update check failed", e);
                    if (manual) activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("Couldn't check for updates");
                        }
                    });
                } finally {
                    synchronized (Updater.this) {
                        checking = false;
                    }
                }
            }
        }, "vt-update-check").start();
    }

    private JSONObject fetchInfo() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "android-latest/update.json").openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Cache-Control", "no-cache");

        try (InputStream in = connection.getInputStream()) {
            return new JSONObject(new String(readFully(in), "UTF-8"));
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readFully(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    // ---- the menu ----

    private void showOffer(String name, final int versionCode, final String apkUrl) {
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("VacuumTube " + name + " (build " + versionCode + ") is available.\nYou have " + activity.versionName()
                        + " (build " + activity.versionCode() + ").")
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        download(apkUrl);
                    }
                })
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putInt(KEY_SKIPPED, versionCode).apply();
                    }
                })
                .show();
    }

    // ---- download and install ----

    @SuppressWarnings("deprecation")
    private void download(final String apkUrl) {
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Downloading update");
        progress.setMessage("Please wait…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = new File(activity.getCacheDir(), "updates");
                final File file = new File(dir, "update.apk");
                HttpURLConnection connection = null;

                try {
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);

                    connection = (HttpURLConnection) new URL(apkUrl).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);

                    final int total = connection.getContentLength();
                    if (total > 0) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                progress.setIndeterminate(false);
                                progress.setMax(total / 1024);
                            }
                        });
                    }

                    long done = 0;
                    try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[32 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            done += read;

                            final int kb = (int) (done / 1024);
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    progress.setProgress(kb);
                                }
                            });
                        }
                    }

                    if (total > 0 && done != total) throw new java.io.IOException("download was cut short");

                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progress.dismiss();
                            install(file);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "download failed", e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progress.dismiss();
                            toast("Download failed, try again later");
                        }
                    });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }, "vt-update-download").start();
    }

    private void install(File file) {
        if (activity.isFinishing()) return;

        // android 8+ asks per app whether it may install other apps, and the setting lives on a separate screen
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = file;
            toast("Allow VacuumTube to install apps, then press Back to continue");

            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                Log.w(TAG, "cannot open the install permission screen", e);
                pendingInstall = null;
                toast("Open Settings and allow VacuumTube to install apps");
            }

            return;
        }

        try {
            Uri uri = Build.VERSION.SDK_INT >= 24
                    ? FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", file)
                    : Uri.fromFile(file);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "cannot start the installer", e);
            toast("This device can't install the update. Download the APK from the releases page instead.");
        }
    }

    /** Called when the app comes back to the front, e.g. after the user allowed installs from the settings screen. */
    void resumePendingInstall() {
        File file = pendingInstall;
        if (file == null) return;

        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) return; // still not allowed

        pendingInstall = null;
        install(file);
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}
