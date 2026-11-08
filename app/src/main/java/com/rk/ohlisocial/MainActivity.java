package com.rk.ohlisocial;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int WEB_PERMISSION_REQUEST = 701;
    private static final int FILE_REQUEST = 702;
    private static final int LOCATION_PERMISSION_REQUEST = 703;
    private static final String SERVER_PREFS = "ohli_server_config";
    private static final String PREF_LAST_SERVER_URL = "last_server_url";
    private static final int SERVER_CONFIG_CONNECT_TIMEOUT_MS = 1000;
    private static final int SERVER_CONFIG_READ_TIMEOUT_MS = 1000;
    private static final int SERVER_CONFIG_MAX_BYTES = 16 * 1024;

    private WebView web;
    private volatile String activeServerUrl = BuildConfig.OHLI_URL;
    private final ExecutorService serverConfigExecutor = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private View splash;
    private View offlineOverlay;
    private ValueCallback<Uri[]> fileCallback;
    // WebView may issue more than one media permission request while a call is
    // starting or while the page reacquires a camera track. Keep each request
    // instead of allowing a later request to overwrite the earlier one.
    private static final class PendingWebPermission {
        final PermissionRequest request;
        final String[] resources;

        PendingWebPermission(PermissionRequest request, String[] resources) {
            this.request = request;
            this.resources = resources;
        }
    }
    private final List<PendingWebPermission> pendingWebPermissions = new ArrayList<>();
    private boolean webPermissionPromptShowing;
    private boolean mainFrameLoadFailed;
    private boolean rendererRecoveryAttempted;
    private boolean backDecisionPending;
    private android.webkit.GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16, 45, 43));
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        root = new FrameLayout(this);
        web = createWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        splash = makeSplash();
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        loadServerConfigurationAndStart();
    }

    /**
     * Resolve the current OHLI endpoint from a small remote JSON file.
     * The request runs once at app startup (and when Retry is pressed), never
     * during camera, microphone, chat, upload, voice-call, video-call, or Live use.
     * If the config service is unavailable, use the last known URL, then the
     * build-time fallback URL.
     */
    private void loadServerConfigurationAndStart() {
        mainFrameLoadFailed = false;
        if (splash != null) splash.setVisibility(View.VISIBLE);
        hideOffline();

        serverConfigExecutor.execute(() -> {
            String selectedUrl = fetchRemoteServerUrl();
            if (!isValidHttpsServerUrl(selectedUrl)) {
                selectedUrl = getLastKnownServerUrl();
            }
            if (!isValidHttpsServerUrl(selectedUrl)) {
                selectedUrl = BuildConfig.OHLI_URL;
            }
            selectedUrl = normalizeServerUrl(selectedUrl);
            final String resolvedUrl = selectedUrl;

            if (isValidHttpsServerUrl(resolvedUrl)) {
                getSharedPreferences(SERVER_PREFS, MODE_PRIVATE)
                        .edit().putString(PREF_LAST_SERVER_URL, resolvedUrl).apply();
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || web == null) return;
                activeServerUrl = resolvedUrl;
                mainFrameLoadFailed = false;
                web.loadUrl(resolvedUrl);
            });
        });
    }

    private String fetchRemoteServerUrl() {
        String configEndpoint = BuildConfig.SERVER_CONFIG_URL;
        if (!isValidHttpsServerUrl(configEndpoint)) return null;
        Uri endpointUri = Uri.parse(configEndpoint);
        String endpointHost = endpointUri.getHost();
        String normalizedEndpointHost = endpointHost == null ? "" : endpointHost.toLowerCase(java.util.Locale.ROOT);
        if (normalizedEndpointHost.isEmpty() || normalizedEndpointHost.startsWith("ganti-")
                || normalizedEndpointHost.startsWith("your-") || normalizedEndpointHost.contains("placeholder")) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(configEndpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(SERVER_CONFIG_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(SERVER_CONFIG_READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Pragma", "no-cache");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return null;

            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > SERVER_CONFIG_MAX_BYTES) return null;
                    output.write(buffer, 0, count);
                }
                JSONObject json = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
                String serverUrl = json.optString("server_url", "").trim();
                return isValidHttpsServerUrl(serverUrl) ? normalizeServerUrl(serverUrl) : null;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String getLastKnownServerUrl() {
        String cached = getSharedPreferences(SERVER_PREFS, MODE_PRIVATE)
                .getString(PREF_LAST_SERVER_URL, null);
        return isValidHttpsServerUrl(cached) ? cached : null;
    }

    private boolean isValidHttpsServerUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().trim().isEmpty()
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeServerUrl(String value) {
        String result = value == null ? BuildConfig.OHLI_URL : value.trim();
        return result.endsWith("/") ? result : result + "/";
    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setGeolocationEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, true);

        view.setBackgroundColor(Color.rgb(247, 250, 249));
        // Disable Android WebView's native long-press action mode. On some older
        // Android/WebView builds, opening the native image context menu can hang
        // or crash the renderer when the image is large. Website touch handlers
        // still receive normal touch events; the page script below only blocks
        // the browser's native image/media context menu and drag operation.
        view.setLongClickable(false);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return routeNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return routeNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                if (v == web) mainFrameLoadFailed = false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (v == web && !mainFrameLoadFailed) {
                    // Prevent Android/browser image context menus and force web
                    // links/popups to stay in this WebView instead of opening a
                    // separate browser tab or leaving OHLI Social.
                    v.evaluateJavascript(
                            "(function(){try{" +
                            "if(window.__ohliClientGuards)return;window.__ohliClientGuards=true;" +
                            "var media='img,picture,video,canvas,[role=img]';" +
                            "var isMedia=function(t){return !!(t&&t.closest&&t.closest(media));};" +
                            "document.addEventListener('contextmenu',function(e){if(isMedia(e.target)){e.preventDefault();e.stopPropagation();}},true);" +
                            "document.addEventListener('dragstart',function(e){if(isMedia(e.target)){e.preventDefault();}},true);" +
                            "var style=document.createElement('style');" +
                            "style.textContent='img,picture,video,canvas,[role=img]{-webkit-touch-callout:none!important;-webkit-user-select:none!important;user-select:none!important;-webkit-user-drag:none!important;}';" +
                            "(document.head||document.documentElement).appendChild(style);" +
                            "document.addEventListener('click',function(e){" +
                            "var a=e.target&&e.target.closest?e.target.closest('a[target=\"_blank\"],area[target=\"_blank\"]'):null;" +
                            "if(a){a.setAttribute('target','_self');}" +
                            "},true);" +
                            "window.open=function(url){try{var a=document.createElement('a');a.href=url;" +
                            "if(a.protocol==='https:'){window.location.href=a.href;return window;}}catch(e){}return null;};" +
                            "}catch(e){}})();", null);
                    rendererRecoveryAttempted = false;
                    hideSplash();
                    hideOffline();
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && v == web) {
                    mainFrameLoadFailed = true;
                    String detail = error != null && error.getDescription() != null
                            ? error.getDescription().toString() : "Periksa koneksi internet kamu.";
                    showOffline(detail);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Recover from a Chromium renderer crash instead of allowing the app process to die.
                if (view != web) {
                    view.destroy();
                    return true;
                }
                String reloadUrl = view.getUrl();
                if (reloadUrl == null || !isHttpsPage(Uri.parse(reloadUrl))) {
                    reloadUrl = activeServerUrl;
                }
                root.removeView(view);
                view.destroy();
                web = null;

                if (rendererRecoveryAttempted) {
                    showOffline("Komponen tampilan web berhenti dua kali. Tutup lalu buka kembali aplikasi.");
                    return true;
                }
                rendererRecoveryAttempted = true;
                mainFrameLoadFailed = false;
                web = createWebView();
                root.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
                if (splash != null) splash.setVisibility(View.VISIBLE);
                web.loadUrl(reloadUrl);
                return true;
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int progress) {
                // The native splash remains visible until the main document finishes loading.
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                handleWebPermissionRequest(request);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> {
                    for (int i = pendingWebPermissions.size() - 1; i >= 0; i--) {
                        if (pendingWebPermissions.get(i).request == request) {
                            pendingWebPermissions.remove(i);
                        }
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                handleGeolocationPrompt(origin, callback);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent picker = params.createIntent();
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(picker, FILE_REQUEST);
                    return true;
                } catch (Exception e) {
                    if (fileCallback != null) fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Pemilih file tidak tersedia", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        });
        return view;
    }

    /** Keep HTTPS page navigation inside OHLI's WebView, including linked pages. */
    private boolean routeNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

        // Internal pages and HTTPS links opened by the OHLI interface stay in this
        // WebView. The previous origin-only rule sent other HTTPS pages to Chrome,
        // which made some in-app arrow buttons appear to exit the application.
        if ("https".equals(scheme) && uri.getHost() != null) return false;

        // Phone and email actions are intentional native actions, not web pages.
        if ("mailto".equals(scheme) || "tel".equals(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show();
            }
        }
        // Block http:, file:, content:, javascript:, intent: and other schemes.
        return true;
    }

    private boolean isHttpsPage(Uri candidate) {
        return candidate != null
                && candidate.getHost() != null
                && "https".equalsIgnoreCase(candidate.getScheme());
    }

    private boolean isTrustedOrigin(Uri candidate) {
        Uri trusted = Uri.parse(activeServerUrl);
        String trustedScheme = trusted.getScheme();
        String trustedHost = trusted.getHost();
        if (trustedScheme == null || trustedHost == null || candidate.getScheme() == null || candidate.getHost() == null) {
            return false;
        }
        int trustedPort = trusted.getPort() == -1 ? ("https".equalsIgnoreCase(trustedScheme) ? 443 : 80) : trusted.getPort();
        int candidatePort = candidate.getPort() == -1 ? ("https".equalsIgnoreCase(candidate.getScheme()) ? 443 : 80) : candidate.getPort();
        return trustedScheme.equalsIgnoreCase(candidate.getScheme())
                && trustedHost.equalsIgnoreCase(candidate.getHost())
                && trustedPort == candidatePort;
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        // PermissionRequest callbacks are handled on the UI thread. Keep the
        // explicit dispatch as a safeguard for vendor WebView implementations.
        runOnUiThread(() -> handleWebPermissionRequestOnUiThread(request));
    }

    private void handleWebPermissionRequestOnUiThread(PermissionRequest request) {
        if (request == null) return;
        if (isFinishing() || isDestroyed() || web == null || !isTrustedOrigin(request.getOrigin())) {
            try { request.deny(); } catch (Exception ignored) { }
            return;
        }

        List<String> allowedResources = new ArrayList<>();
        List<String> missingRuntimePermissions = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                allowedResources.add(resource);
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                        && !missingRuntimePermissions.contains(Manifest.permission.CAMERA)) {
                    missingRuntimePermissions.add(Manifest.permission.CAMERA);
                }
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                allowedResources.add(resource);
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                        && !missingRuntimePermissions.contains(Manifest.permission.RECORD_AUDIO)) {
                    missingRuntimePermissions.add(Manifest.permission.RECORD_AUDIO);
                }
            } else {
                // Keep the allow-list strict: do not grant unimplemented WebView resources.
                try { request.deny(); } catch (Exception ignored) { }
                return;
            }
        }

        if (allowedResources.isEmpty()) {
            try { request.deny(); } catch (Exception ignored) { }
            return;
        }

        if (missingRuntimePermissions.isEmpty()) {
            try { request.grant(allowedResources.toArray(new String[0])); }
            catch (Exception ignored) { try { request.deny(); } catch (Exception ignoredAgain) { } }
            return;
        }

        pendingWebPermissions.add(new PendingWebPermission(
                request, allowedResources.toArray(new String[0])));

        // Ask for both media permissions in one system dialog. Android only
        // prompts for permissions that have not already been granted. This
        // also covers a second camera/microphone request arriving while the
        // first permission dialog is visible.
        if (!webPermissionPromptShowing) {
            webPermissionPromptShowing = true;
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            }, WEB_PERMISSION_REQUEST);
        }
    }

    private void handleGeolocationPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
        Uri requested = Uri.parse(origin);
        if (!isTrustedOrigin(requested)) {
            callback.invoke(origin, false, false);
            return;
        }
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            callback.invoke(origin, true, false);
            return;
        }
        if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
        pendingGeoOrigin = origin;
        pendingGeoCallback = callback;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
    }

    private View makeSplash() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.rgb(247, 250, 249));

        TextView mark = new TextView(this);
        mark.setText("OHLI");
        mark.setTextColor(Color.rgb(16, 75, 66));
        mark.setTextSize(38);
        mark.setTypeface(null, 1);
        mark.setGravity(Gravity.CENTER);
        box.addView(mark);

        TextView sub = new TextView(this);
        sub.setText("SOCIAL");
        sub.setTextColor(Color.rgb(22, 138, 112));
        sub.setTextSize(13);
        sub.setLetterSpacing(.28f);
        sub.setGravity(Gravity.CENTER);
        box.addView(sub);

        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        spinnerParams.topMargin = dp(26);
        box.addView(spinner, spinnerParams);

        TextView caption = new TextView(this);
        caption.setText("Menghubungkan ke OHLI Social");
        caption.setTextColor(Color.rgb(105, 125, 120));
        caption.setTextSize(13);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-2, -2);
        captionParams.topMargin = dp(14);
        box.addView(caption, captionParams);
        return box;
    }

    private void hideSplash() {
        if (splash != null) splash.setVisibility(View.GONE);
    }

    private void showOffline(String detail) {
        hideSplash();
        hideOffline();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(28), dp(28), dp(28), dp(28));
        panel.setBackgroundColor(Color.rgb(247, 250, 249));

        TextView title = new TextView(this);
        title.setText("OHLI Social belum terhubung");
        title.setTextColor(Color.rgb(16, 75, 66));
        title.setTextSize(21);
        title.setTypeface(null, 1);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);

        TextView message = new TextView(this);
        message.setText("Periksa internet atau pastikan server OHLI sedang aktif, lalu coba lagi.\n\n" + detail);
        message.setTextColor(Color.rgb(85, 105, 100));
        message.setTextSize(14);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(12), 0, dp(18));
        panel.addView(message);

        Button retry = new Button(this);
        retry.setText("Coba lagi");
        retry.setOnClickListener(v -> {
            mainFrameLoadFailed = false;
            rendererRecoveryAttempted = false;
            hideOffline();
            if (web == null) {
                web = createWebView();
                root.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
            }
            if (splash != null) splash.setVisibility(View.VISIBLE);
            loadServerConfigurationAndStart();
        });
        panel.addView(retry, new LinearLayout.LayoutParams(-2, -2));

        offlineOverlay = panel;
        root.addView(offlineOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void hideOffline() {
        if (offlineOverlay != null && root != null) {
            root.removeView(offlineOverlay);
            offlineOverlay = null;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WEB_PERMISSION_REQUEST) {
            webPermissionPromptShowing = false;
            boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            boolean audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;

            List<PendingWebPermission> requests = new ArrayList<>(pendingWebPermissions);
            pendingWebPermissions.clear();
            for (PendingWebPermission pending : requests) {
                PermissionRequest request = pending.request;
                List<String> resourcesToGrant = new ArrayList<>();
                for (String resource : pending.resources) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && cameraGranted) {
                        resourcesToGrant.add(resource);
                    } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && audioGranted) {
                        resourcesToGrant.add(resource);
                    }
                }
                try {
                    if (!resourcesToGrant.isEmpty() && !isFinishing() && !isDestroyed()
                            && isTrustedOrigin(request.getOrigin())) {
                        request.grant(resourcesToGrant.toArray(new String[0]));
                    } else {
                        request.deny();
                    }
                } catch (Exception ignored) { }
            }
        } else if (requestCode == LOCATION_PERMISSION_REQUEST && pendingGeoCallback != null) {
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            android.webkit.GeolocationPermissions.Callback callback = pendingGeoCallback;
            String origin = pendingGeoOrigin;
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
            callback.invoke(origin, granted, false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            Uri[] selected = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    selected = new Uri[count];
                    for (int i = 0; i < count; i++) selected[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    selected = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(selected);
            fileCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    /**
     * Android system Back is handled in this order:
     * 1. Close Story / dialog overlays.
     * 2. Move back through OHLI Social's own page stack (including bottom-nav pages).
     * 3. Return from the legacy Chat / Contacts / Calls area to OHLI Social.
     * 4. Fall back to the WebView navigation stack, then ask before exiting.
     *
     * The website uses a single-page app router, so WebView.canGoBack() alone is
     * not enough: switching OHLI tabs does not necessarily create WebView history.
     */
    @Override
    public void onBackPressed() {
        handleSystemBack();
    }

    private void handleSystemBack() {
        if (backDecisionPending) return;
        if (web == null) {
            showExitConfirmation();
            return;
        }
        backDecisionPending = true;
        final WebView currentWeb = web;
        final String backScript = "(function(){try{" +
                "var visible=function(e){return !!e&&!e.hidden&&getComputedStyle(e).display!=='none'&&getComputedStyle(e).visibility!=='hidden';};" +
                "var shell=document.getElementById('ohliSocialShell');" +
                "if(visible(shell)){" +
                  "var story=shell.querySelector('#ohliStoryViewer');" +
                  "if(visible(story)){var sc=story.querySelector('[data-action=\"close-story\"]');if(sc){sc.click();return true;}}" +
                  "var socialModal=shell.querySelector('#ohliModalBackdrop');" +
                  "if(visible(socialModal)){var mc=socialModal.querySelector('[data-action=\"close-modal\"]');if(mc){mc.click();return true;}socialModal.click();return true;}" +
                  "var pageBack=shell.querySelector('.ohli-internal-back');" +
                  "if(pageBack&&!pageBack.hidden){pageBack.click();return true;}" +
                  "var current=shell.getAttribute('data-page')||'home';" +
                  "if(current!=='home'){var home=shell.querySelector('.ohli-nav-link[data-page=\"home\"]');if(home){home.click();return true;}}" +
                  "return false;" +
                "}" +
                "var legacy=document.getElementById('app');" +
                "if(visible(legacy)){" +
                  "var legacyModal=document.getElementById('modalBackdrop');" +
                  "if(legacyModal&&legacyModal.classList.contains('open')){" +
                    "var close=legacyModal.querySelector('[data-action=\"close\"],.modal-close,#rkmMoreClose');" +
                    "if(close){close.click();return true;}" +
                    "return false;" +
                  "}" +
                  "if(legacy.classList.contains('page-open')||legacy.classList.contains('chat-open')){" +
                    "var lb=document.getElementById('backButton');if(lb){lb.click();return true;}" +
                  "}" +
                  "var socialReturn=document.getElementById('ohliBackSocial');" +
                  "if(socialReturn&&getComputedStyle(socialReturn).display!=='none'){socialReturn.click();return true;}" +
                "}" +
                "return false;" +
                "}catch(e){return false;}})();";
        try {
            currentWeb.evaluateJavascript(backScript, value -> {
                backDecisionPending = false;
                if (isJavascriptTrue(value)) return;
                if (web != null && web == currentWeb && web.canGoBack()) {
                    web.goBack();
                } else {
                    showExitConfirmation();
                }
            });
        } catch (Exception ignored) {
            backDecisionPending = false;
            if (web != null && web.canGoBack()) web.goBack();
            else showExitConfirmation();
        }
    }

    private boolean isJavascriptTrue(String value) {
        return "true".equals(value) || "\"true\"".equals(value);
    }

    private void showExitConfirmation() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setMessage("Keluar dari OHLI Social?")
                .setNegativeButton("Batal", (dialog, which) -> { })
                .setPositiveButton("Keluar", (dialog, which) -> finish())
                .show();
    }

    @Override
    protected void onDestroy() {
        backDecisionPending = false;
        serverConfigExecutor.shutdownNow();
        for (PendingWebPermission pending : new ArrayList<>(pendingWebPermissions)) {
            try { pending.request.deny(); } catch (Exception ignored) { }
        }
        pendingWebPermissions.clear();
        if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
        pendingGeoCallback = null;
        pendingGeoOrigin = null;
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = null;
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.removeAllViews();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
