package com.ytplayer;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout fullscreenContainer;

    private AudioService audioService;
    private boolean serviceBound = false;

    private static final String YOUTUBE_URL = "https://m.youtube.com";
    private static final int NOTIF_PERM_REQ = 100;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            AudioService.LocalBinder lb = (AudioService.LocalBinder) binder;
            audioService = lb.getService();
            serviceBound = true;
            // Give the service a reference to our WebView
            audioService.setWebView(webView);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progressBar        = findViewById(R.id.progressBar);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        webView            = findViewById(R.id.webView);

        requestNotificationPermission();
        setupWebView();
        loadYouTube();
        startAudioService();
    }

    // ── WebView Setup ─────────────────────────────────────────────────────────

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // allow autoplay
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Desktop UA gives better YouTube layout
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; Pixel 4) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.6099.210 Mobile Safari/537.36"
        );

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // Keep YouTube & Google sign-in inside app
                if (url.contains("youtube.com") || url.contains("youtu.be") ||
                    url.contains("google.com") || url.contains("accounts.")) {
                    return false;
                }
                // Everything else → external browser
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                // Inject script every page load
                injectVisibilityOverride();
                // Mark playing state
                if (serviceBound) audioService.setWebView(webView);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            private View customView;
            private CustomViewCallback customCb;

            @Override
            public void onProgressChanged(WebView view, int p) {
                progressBar.setProgress(p);
                if (p == 100) progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Grant audio/video permissions to the WebView
                request.grant(request.getResources());
            }

            // ── Full-screen video ──────────────────────────────────────────
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                customView = view;
                customCb   = callback;
                webView.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                showSystemUI();
                if (customCb != null) customCb.onCustomViewHidden();
                customView = null;
                customCb   = null;
            }
        });
    }

    /**
     * Override YouTube's Page Visibility API inside the WebView.
     * Without this YouTube will pause the video the moment the screen turns off
     * or the app is minimized, because it thinks the page is hidden.
     */
    private void injectVisibilityOverride() {
        String js =
            "(function() {" +
            // Make document always appear visible
            "  try { Object.defineProperty(document, 'hidden', " +
            "    { get: function(){ return false; }, configurable: true }); } catch(e){}" +
            "  try { Object.defineProperty(document, 'visibilityState', " +
            "    { get: function(){ return 'visible'; }, configurable: true }); } catch(e){}" +
            "  try { Object.defineProperty(document, 'webkitHidden', " +
            "    { get: function(){ return false; }, configurable: true }); } catch(e){}" +
            "  try { Object.defineProperty(document, 'webkitVisibilityState', " +
            "    { get: function(){ return 'visible'; }, configurable: true }); } catch(e){}" +
            // Stop visibility-change events reaching YouTube's listener
            "  document.addEventListener('visibilitychange', " +
            "    function(e){ e.stopImmediatePropagation(); }, true);" +
            "  document.addEventListener('webkitvisibilitychange', " +
            "    function(e){ e.stopImmediatePropagation(); }, true);" +
            "  document.dispatchEvent(new Event('visibilitychange'));" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private void loadYouTube() {
        webView.loadUrl(YOUTUBE_URL);
    }

    // ── Service ───────────────────────────────────────────────────────────────

    private void startAudioService() {
        Intent intent = new Intent(this, AudioService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIF_PERM_REQ);
            }
        }
    }

    // ── System UI ─────────────────────────────────────────────────────────────

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private void showSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
        injectVisibilityOverride();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // CRITICAL: Do NOT call webView.onPause() — that stops media.
        // Do NOT call webView.pauseTimers() — that kills JS execution.
        // The service + wake lock handles keeping audio alive.
        if (serviceBound) audioService.keepAlive();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // App going to background — trigger keepAlive injection again
        if (serviceBound) audioService.keepAlive();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(connection);
            serviceBound = false;
        }
        // Don't destroy WebView fully — let service keep audio going
    }

    // ── Back button ───────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            // Move to background — audio keeps playing
            moveTaskToBack(true);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
