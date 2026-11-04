package io.github.cheapearth9.vacuumtube;

import android.Manifest;
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
        settings.setUserAgentString(userAgent);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(this), "AndroidBridge");
        webView.setWebViewClient(new TvClient());
        webView.setWebChromeClient(new TvChrome());

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        webView.loadUrl(TV_URL);
    }

    // ---- called by the bridge (on the UI thread) ----

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

        return super.onKeyDown(keyCode, event);
    }

    /** The back button can't reach the page as a key event, so the injected script synthesizes cobalt's back key. */
    private void goBack() {
        String url = webView.getUrl();
        if (url == null || !url.startsWith(TV_URL)) {
            finish();
            return;
        }

        webView.evaluateJavascript(
                "(function(){if(window.__vtBack){window.__vtBack();return true}return false})()",
                value -> {
                    if (!"true".equals(value)) { // the script isn't there, so fall back to plain webview history
                        if (webView.canGoBack()) webView.goBack();
                        else finish();
                    }
                });
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
            Log.w(TAG, "could not patch the page, loading it as is", e);
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

            // the page loaded without our tag (the fetch above failed), so inject late: not as clean, but better than nothing
            view.evaluateJavascript("window.__vtLoaded===true", value -> {
                byte[] data = bundle();
                if (!"true".equals(value) && data != null && webView != null) {
                    Log.w(TAG, "bundle tag was missing, injecting after load");
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
            Log.d(TAG, message.sourceId() + ":" + message.lineNumber() + " " + message.message());
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
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsMicrophone = true;
            }

            if (!fromYouTube || !wantsMicrophone) { // voice search is the only thing that's allowed
                request.deny();
                return;
            }

            if (hasMicrophonePermission()) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPermission = request;
                askForMicrophone();
            }
        }
    }
}
