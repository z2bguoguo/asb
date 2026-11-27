package cn.z2b.asb;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

public final class GuestActivity extends Activity {
    private static final String HOST = "asb.invalid";
    private static final int PICK_MEDIA = 100;
    private static final Map<String, String> ASSETS = Map.of(
            "/index.html", "text/html",
            "/console.css", "text/css",
            "/console.mjs", "text/javascript",
            "/exchange.mjs", "text/javascript",
            "/libv86.mjs", "text/javascript",
            "/v86.wasm", "application/wasm",
            "/v86-fallback.wasm", "application/wasm",
            "/seabios.bin", "application/octet-stream",
            "/vgabios.bin", "application/octet-stream"
    );
    private WebView web;
    private ValueCallback<Uri[]> pendingFiles;
    private boolean active;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        // A user-selected SAF document is passed to the file input. Page-level content URLs
        // are rejected by the client below, in addition to CSP and the asset allowlist.
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String path = uri.getEncodedPath();
                if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getEncodedAuthority())
                        || !"GET".equals(request.getMethod()) || path == null || !ASSETS.containsKey(path)
                        || uri.getQuery() != null) {
                    return rejected(403, "Forbidden");
                }
                String type = ASSETS.get(path);
                String encoding = type.startsWith("text/") ? "UTF-8" : null;
                try {
                    return new WebResourceResponse(type, encoding, 200, "OK", Map.of(
                            "Cache-Control", "no-store",
                            "X-Content-Type-Options", "nosniff",
                            "Content-Security-Policy", "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; "
                                    + "style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'none'; "
                                    + "worker-src 'self' blob:; frame-src 'none'; object-src 'none'; "
                                    + "base-uri 'none'; form-action 'none'"),
                            getAssets().open(path.substring(1)));
                } catch (IOException exception) {
                    return rejected(404, "Not Found");
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                sendFocus();
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                setContentView(new android.widget.FrameLayout(GuestActivity.this));
                web.destroy();
                web = null;
                Toast.makeText(GuestActivity.this, "虚拟机渲染进程已结束，请降低来宾内存后重试", Toast.LENGTH_LONG).show();
                finish();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                        FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(pick, PICK_MEDIA);
                } catch (android.content.ActivityNotFoundException exception) {
                    pendingFiles = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }

            @Override public void onPermissionRequest(android.webkit.PermissionRequest request) {
                request.deny();
            }
        });
        setContentView(web);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        web.loadUrl("https://" + HOST + "/index.html");
    }

    private static WebResourceResponse rejected(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason, Map.of(),
                new ByteArrayInputStream(new byte[0]));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MEDIA || pendingFiles == null) return;
        Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        pendingFiles.onReceiveValue(uri != null && "content".equals(uri.getScheme()) ? new Uri[]{uri} : null);
        pendingFiles = null;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        sendFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        sendFocus();
    }

    @Override protected void onPause() {
        active = false;
        sendFocus();
        super.onPause();
    }

    private void sendFocus() {
        if (web != null) {
            web.evaluateJavascript("window.setHostFocus?.(" + (active && hasWindowFocus()) + ")", null);
        }
    }

    @Override protected void onDestroy() {
        if (pendingFiles != null) {
            pendingFiles.onReceiveValue(null);
            pendingFiles = null;
        }
        if (web != null) {
            setContentView(new android.widget.FrameLayout(this));
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
