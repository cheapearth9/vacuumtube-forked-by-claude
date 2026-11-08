package io.github.cheapearth9.vacuumtube;

import android.Manifest;
import android.widget.Toast;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube for TV in a full screen WebView, with VacuumTube's ad blocker and settings menu injected.
 *
 * How the injection works: the desktop app uses an electron preload script, which runs before any page script and can
 * strip the page's content security policy. A WebView has neither, so this activity answers the main request for
 * /tv itself: it fetches the page, drops the CSP headers, and adds a script tag (served from assets/vacuumtube.js) as the
 * first thing in the head, which gives the modules the same "run before youtube" timing they have on desktop.
 */
public class MainActivity extends Activity {
    private static final String TAG = "VacuumTube";
    static final String TV_URL = "https://www.youtube.com/tv";

    private static final String BUNDLE_PATH = "/__vt/bundle.js";
    private static final String BUNDLE_TAG = "<script src=\"" + BUNDLE_PATH + "\"></script>";
    private static final Pattern HEAD_TAG = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final int REQUEST_MICROPHONE = 1;

    // same list as src/ua.js: the hosts that get the TV user agent (and the only ones the app will navigate to)
    private static final String[] GOOGLE_HOSTS = {
            "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com", "google.com", "googleapis.com",
            "gstatic.com", "doubleclick.net", "googleusercontent.com", "youtube-nocookie.com"
    };

    private static final String PAUSE_VIDEO_JS =
            "(function(){try{if(window.__vtEmit)window.__vtEmit('blur');"
                    + "var v=document.querySelector('video');if(v&&!v.paused)v.pause();}catch(e){}})()";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retryLoad = new Runnable() {
        @Override
        public void run() {
            if (webView != null) webView.loadUrl(TV_URL);
        }
    };

    private WebView webView;
    private String userAgent;
    private byte[] bundle;
    private Updater updater;
    private Bridge bridge;

    /** Logging is off unless "Debug Info" is switched on in the settings (real crashes are still logged with Log.e). */
    void debugLog(String message) {
        try {
            if (bridge != null && bridge.flag("debug_info")) Log.i(TAG, message);
        } catch (RuntimeException ignored) {
        }
    }

    void debugLog(String message, Throwable error) {
        try {
            if (bridge != null && bridge.flag("debug_info")) Log.w(TAG, message, error);
        } catch (RuntimeException ignored) {
        }
    }
    private String webViewChrome = "?";
    private String lastScriptError;
    private boolean statusShown;
    private boolean updateCheckScheduled;
    private PermissionRequest pendingPermission;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        userAgent = UserAgent.build(getPackageName(), versionName().split("-")[0]);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        java.util.regex.Matcher chrome = Pattern.compile("Chrome/(\\d+)").matcher(settings.getUserAgentString());
        if (chrome.find()) webViewChrome = chrome.group(1);

        settings.setUserAgentString(userAgent);
        settings.setTextZoom(100); // the tv's font size setting would otherwise scale youtube's text, and its layout overflows
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        updater = new Updater(this);
        int chromeMajor = 0;
        try {
            chromeMajor = Integer.parseInt(webViewChrome);
        } catch (NumberFormatException ignored) {
        }

        bridge = new Bridge(this, chromeMajor);
        webView.addJavascriptInterface(bridge, "AndroidBridge");
        webView.setWebViewClient(new TvClient());
        webView.setWebChromeClient(new TvChrome());

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        webView.loadUrl(TV_URL);
    }

    // ---- called by the bridge (on the UI thread) ----

    int versionCode() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    void checkForUpdates(boolean manual) {
        if (updater == null) return;

        if (manual) updater.check(true);
        else updater.checkIfDue();
    }

    String versionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name != null ? name : "2.0.2";
        } catch (PackageManager.NameNotFoundException e) {
            return "2.0.2";
        }
    }

    void reloadTv() {
        String url = webView.getUrl();
        if (url != null && url.startsWith(TV_URL)) webView.reload();
        else webView.loadUrl(TV_URL);
    }

    boolean hasMicrophonePermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @TargetApi(23)
    void askForMicrophone() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MICROPHONE || pendingPermission == null) return;

        PermissionRequest request = pendingPermission;
        pendingPermission = null;

        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else request.deny();
    }

    // ---- remote control / lifecycle ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) goBack();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getRepeatCount() == 0) {
                webView.evaluateJavascript("window.vtToggleSettingsOverlay && window.vtToggleSettingsOverlay()", null);
            }

            return true; // the menu key on the remote opens VacuumTube's settings, even if youtube's own entry is missing
        }

        return super.onKeyDown(keyCode, event);
    }

    private boolean backCheckPending;

    /**
     * Back is sent to the page (as cobalt's back key, or a real Escape key when the scripts aren't running).
     * If nothing at all changed on the page afterwards, there was nowhere left to go back to (the home screen's exit
     * prompt, the sign-in screen...), so that press exits the app: keep pressing Back and the last press leaves.
     */
    private void goBack() {
        String url = webView.getUrl();
        if (url == null || !url.startsWith(TV_URL)) {
            debugLog("closing: back pressed outside youtube tv, url=" + url);
            finish();
            return;
        }

        final boolean startWatching = !backCheckPending;
        backCheckPending = true;

        // watch for any change on the page (and a changed address), then press back with the scripts' help if they run
        String script = "(function(){"
                + (startWatching
                ? "var t0={},a0=document.getElementsByTagName('*');for(var j=0;j<a0.length;j++){var q=a0[j].tagName.toLowerCase();if(q.indexOf('ytlr-')===0)t0[q]=1}"
                + "var pk=false,pe=document.querySelectorAll('ytlr-account-selector,ytlr-carousel-account');for(var k=0;k<pe.length;k++){var r=pe[k].getBoundingClientRect(),cs=getComputedStyle(pe[k]);if(r.width>0&&r.height>0&&cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity)>0.1)pk=true}"
                + "var o0=document.getElementById('vt-settings-overlay-root');var w=window.__vtWatch={picker:pk,before:Object.keys(t0).sort().join(' '),changed:false,href:location.href,mo:null,overlay:o0&&!o0.classList.contains('vt-settings-hidden')?o0:null};"
                + "w.mo=new MutationObserver(function(){w.changed=true});"
                + "w.mo.observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});"
                : "")
                + "if(window.__vtBack){window.__vtBack();return true}return false})()";

        webView.evaluateJavascript(script, new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"true".equals(value)) { // the scripts aren't there: send a real escape key, which youtube treats as back
                    long now = android.os.SystemClock.uptimeMillis();
                    webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0));
                    webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0));
                }
            }
        });

        if (!startWatching) return; // a check from a moment ago is still running and will see this press too

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;

                webView.evaluateJavascript(
                        "(function(){var w=window.__vtWatch;if(!w)return 'unknown';w.mo.disconnect();window.__vtWatch=null;"
                                + "var changed=w.changed||location.href!==w.href;"
                                + "var tags={},all=document.getElementsByTagName('*');"
                                + "for(var i=0;i<all.length;i++){var t=all[i].tagName.toLowerCase();if(t.indexOf('ytlr-')===0)tags[t]=1}"
                                + "var names=Object.keys(tags);"
                                // the sign-in screen and the account (profile) picker are the only screens where Back leaves the app
                                + "var o=w.overlay;"
                                // the account picker ("who's watching") is the only screen where back leaves the app. It is recognised by what was on screen
                                // when back was pressed (a visible account picker; a video page may be loaded behind it), not by what is there
                                // afterwards: youtube may already have moved on to the page behind it. The menu being open never counts.
                                + "var b=w.before;"
                                + "var login=w.picker&&!o;"
                                + "if(login)changed=false;"
                                + "if(!login&&window.__vtDiag)window.__vtDiag('Back pressed, app stays open. picker-visible-before='+w.picker+' changed='+changed+' '+location.hash+' | BEFORE: '+w.before+' | AFTER: '+names.join(' '));"
                                + "return (!changed&&login)?'exit':'stay'})()",
                        new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                backCheckPending = false;
                                if (value != null && value.contains("exit") && !isFinishing()) {
                                    debugLog("closing: back pressed at the sign-in screen");
                                    finish();
                                }
                            }
                        });
            }
        }, 600);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updater != null) updater.resumePendingInstall();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onStop() {
        super.onStop();
        webView.evaluateJavascript(PAUSE_VIDEO_JS, null); // leaving to the home screen shouldn't leave audio playing
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        debugLog("onDestroy, finishing=" + isFinishing() + ", url=" + (webView != null ? webView.getUrl() : null));
        handler.removeCallbacks(retryLoad);
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }

    // ---- the injected script ----

    private synchronized byte[] bundle() {
        if (bundle != null) return bundle;

        try (InputStream in = getAssets().open("vacuumtube.js")) {
            bundle = readFully(in);
        } catch (Exception e) {
            Log.e(TAG, "could not read assets/vacuumtube.js", e);
        }

        return bundle;
    }

    private static byte[] readFully(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static WebResourceResponse empty(int status, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", status, reason, new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse bundleResponse() {
        byte[] data = bundle();
        if (data == null) return empty(404, "Not Found");

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/javascript", "utf-8", 200, "OK", headers, new ByteArrayInputStream(data));
    }

    /**
     * Fetches the leanback page ourselves so we can drop its CSP and put the bundle tag first in the head.
     * Returns null (letting the WebView load the page normally) for anything unexpected: redirects, errors, odd content.
     */
    private WebResourceResponse patchedDocument(String urlString, Map<String, String> requestHeaders) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);

            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                String name = header.getKey().toLowerCase(Locale.US);
                // accept-encoding is left to HttpURLConnection so that it decompresses the body for us
                if (name.equals("accept-encoding") || name.equals("cookie") || name.equals("host")) continue;
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            connection.setRequestProperty("User-Agent", userAgent);

            CookieManager cookies = CookieManager.getInstance();
            String cookie = cookies.getCookie(urlString);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);

            int status = connection.getResponseCode();

            Map<String, List<String>> responseHeaders = connection.getHeaderFields();
            for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
                if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String value : header.getValue()) cookies.setCookie(urlString, value);
                }
            }

            String type = connection.getContentType();
            if (status != 200 || type == null || !type.toLowerCase(Locale.US).startsWith("text/html")) return null;

            String html = new String(readFully(connection.getInputStream()), "UTF-8");
            Matcher head = HEAD_TAG.matcher(html);
            if (!head.find()) return null;

            String patched = html.substring(0, head.end()) + BUNDLE_TAG + html.substring(head.end());

            Map<String, String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
                if (header.getKey() == null) continue; // the status line

                String name = header.getKey().toLowerCase(Locale.US);
                if (name.startsWith("content-security-policy")
                        || name.equals("content-length") || name.equals("content-encoding") || name.equals("content-type")
                        || name.equals("transfer-encoding") || name.equals("set-cookie")
                        || name.equals("connection") || name.equals("keep-alive")) continue;

                headers.put(header.getKey(), TextUtils.join(", ", header.getValue()));
            }

            return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers, new ByteArrayInputStream(patched.getBytes("UTF-8")));
        } catch (Exception e) {
            debugLog("could not patch the page, loading it as is", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isGoogleHost(String host) {
        if (host == null) return false;

        for (String google : GOOGLE_HOSTS) {
            if (host.equals(google) || host.endsWith("." + google)) return true;
        }

        return false;
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void showOffline(String description) {
        String html = "<html><body style=\"background:#000;color:#fff;font-family:sans-serif;margin:0;height:100vh;"
                + "display:flex;align-items:center;justify-content:center;text-align:center\"><div>"
                + "<h1>Can't reach YouTube</h1><p>" + escapeHtml(description) + "</p><p>Trying again…</p></div></body></html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        handler.removeCallbacks(retryLoad);
        handler.postDelayed(retryLoad, 5000);
    }

    /** A one-time summary on screen, so a photo of the tv is enough to see why something doesn't work. */
    private void showStatus() {
        if (webView == null || isFinishing() || !bridge.flag("debug_info")) return;

        webView.evaluateJavascript("window.__vtLoaded===true", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                boolean loaded = "true".equals(value);

                String line1 = "VacuumTube " + versionName() + " (build " + versionCode() + ") | Android " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ") | WebView Chrome " + webViewChrome;
                String line2 = "scripts: " + (loaded ? "OK" : "NOT RUNNING" + (lastScriptError != null ? " - " + lastScriptError : ""));
                String line3 = "VP9 hw: " + Devices.hasHardwareDecoder(Devices.VP9) + " | AV1 hw: " + Devices.hasHardwareDecoder(Devices.AV1)
                        + " | h264ify: " + bridge.flag("h264ify") + " | low memory mode: " + bridge.flag("low_memory_mode");

                Toast.makeText(MainActivity.this, line1 + "\n" + line2 + "\n" + line3, Toast.LENGTH_LONG).show();
                Toast.makeText(MainActivity.this, line1 + "\n" + line2 + "\n" + line3, Toast.LENGTH_LONG).show(); // queued: stays up about 7 seconds
            }
        });
    }

    // ---- web clients ----

    private class TvClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost();
            if (host == null) return null;

            // same as the desktop app: google's csp reporting endpoints are for cobalt, don't feed them our traffic
            if (host.equals("csp.withgoogle.com")) return empty(204, "No Content");

            if (host.equals("www.youtube.com")) {
                String path = uri.getPath();
                if (BUNDLE_PATH.equals(path)) return bundleResponse();

                if (request.isForMainFrame() && "/tv".equals(path) && "GET".equals(request.getMethod())) {
                    return patchedDocument(uri.toString(), request.getRequestHeaders());
                }
            }

            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !allowNavigation(Uri.parse(url));
        }

        @TargetApi(24)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !allowNavigation(request.getUrl());
        }

        private boolean allowNavigation(Uri uri) {
            return "https".equals(uri.getScheme()) && isGoogleHost(uri.getHost()); // true = let the webview load it
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush();

            if (url == null || !url.startsWith(TV_URL)) return;

            if (!statusShown) {
                statusShown = true;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        showStatus();
                    }
                }, 6000);
            }

            if (!updateCheckScheduled) { // look for a new version once the app has settled, so it never slows the start
                updateCheckScheduled = true;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkForUpdates(false);
                    }
                }, 3000);
            }

            // the page loaded without our tag (the fetch above failed), so inject late: not as clean, but better than nothing
            view.evaluateJavascript("window.__vtLoaded===true", value -> {
                byte[] data = bundle();
                if (!"true".equals(value) && data != null && webView != null) {
                    debugLog("bundle tag was missing, injecting after load");
                    webView.evaluateJavascript(new String(data, java.nio.charset.Charset.forName("UTF-8")), null);
                }
            });
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            // the old signature is called for the main frame on every api level (the newer one only from api 23)
            if (failingUrl != null && failingUrl.startsWith(TV_URL)) showOffline(description);
        }

        @TargetApi(26)
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.e(TAG, "the web process died, restarting");
            handler.post(new Runnable() {
                @Override
                public void run() {
                    recreate();
                }
            });
            return true;
        }
    }

    private class TvChrome extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            debugLog(message.sourceId() + ":" + message.lineNumber() + " " + message.message());

            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && lastScriptError == null) {
                String source = message.sourceId() == null ? "" : message.sourceId();
                if (source.contains("__vt") || message.message().contains("SyntaxError") || message.message().contains("[vt]")) {
                    String text = message.message();
                    lastScriptError = text.length() > 160 ? text.substring(0, 160) : text;
                }
            }
            return true;
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // no default "play" placeholder over the video
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            Uri origin = request.getOrigin();
            boolean fromYouTube = origin != null && "www.youtube.com".equals(origin.getHost());

            boolean wantsMicrophone = false;
            boolean wantsProtectedMedia = false;
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsMicrophone = true;
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) wantsProtectedMedia = true;
            }

            // only youtube, and only for what it needs: voice search, and the DRM identifier that protected (encrypted) streams use
            if (!fromYouTube || (!wantsMicrophone && !wantsProtectedMedia)) {
                request.deny();
                return;
            }

            if (wantsMicrophone) {
                if (hasMicrophonePermission()) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingPermission = request;
                    askForMicrophone();
                }
            } else {
                request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
            }
        }
    }
}
