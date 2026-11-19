package com.ideacreative.hrga;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 101;
    private static final int REQ_PERM_LOCATION = 201;
    private static final int REQ_PERM_CAMERA = 202;

    private WebView webView;
    private SwipeRefreshLayout swipe;
    private ProgressBar progress;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;
    private WebChromeClient.FileChooserParams pendingChooserParams;

    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private PermissionRequest pendingWebPermission;

    private boolean showingOffline = false;

    /**
     * Halaman luar Apps Script menyisipkan banner peringatan Google di atas iframe aplikasi
     * (muncul belakangan sehingga tampilan "bergeser"). Script ini menyembunyikan semua elemen
     * selain iframe aplikasi dan membuat iframe selalu penuh satu layar, termasuk banner yang
     * disisipkan setelah halaman selesai dimuat.
     */
    private static final String FIT_SCRIPT =
        "(function(){try{" +
        "if(!document.getElementById('hrga-fit')){var st=document.createElement('style');st.id='hrga-fit';" +
        "st.textContent='html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;overscroll-behavior:none!important;background:#F8F4EE}';" +
        "(document.head||document.documentElement).appendChild(st);}" +
        "function fit(){var f=document.getElementById('sandboxFrame')||document.querySelector('iframe');if(!f)return;" +
        "var el=f;while(el&&el!==document.body&&el.parentElement){var p=el.parentElement;" +
        "for(var i=0;i<p.children.length;i++){var c=p.children[i];if(c!==el&&c.tagName!=='SCRIPT'&&c.tagName!=='STYLE'&&c.tagName!=='LINK')c.style.setProperty('display','none','important');}" +
        "el.style.setProperty('margin','0','important');el.style.setProperty('padding','0','important');el=p;}" +
        "f.style.cssText='position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100%!important;border:0!important;margin:0!important;z-index:1';}" +
        "fit();if(!window.__hrgaObs){window.__hrgaObs=new MutationObserver(fit);window.__hrgaObs.observe(document.documentElement,{childList:true,subtree:true});}" +
        "}catch(e){}})();";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Edge-to-edge (wajib untuk targetSdk 35+): beri padding sesuai status bar & nav bar
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        webView = findViewById(R.id.webview);
        swipe = findViewById(R.id.swipe);
        progress = findViewById(R.id.progress);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " HRGAApp/" + BuildConfig.VERSION_NAME);
        s.setTextZoom(100);              // ukuran font sistem besar tidak merusak tata letak
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true); // GAS memuat isi di iframe googleusercontent

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());

        // Tarik-untuk-refresh DIMATIKAN: isi GAS berjalan di dalam iframe sehingga WebView selalu
        // terlihat "di paling atas" dan setiap scroll ke atas memicu reload. Pakai tombol refresh di aplikasi.
        swipe.setEnabled(false);
        // Hilangkan efek "layar ikut tertarik/bergeser" saat mentok scroll
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else loadApp();
    }

    private void loadApp() {
        if (!isOnline()) { showOffline(); return; }
        showingOffline = false;
        webView.loadUrl(BuildConfig.APP_URL);
    }

    private void reload() {
        if (showingOffline || webView.getUrl() == null) loadApp(); else webView.reload();
    }

    private void showOffline() {
        showingOffline = true;
        webView.loadUrl("file:///android_asset/offline.html");
        swipe.setRefreshing(false);
        progress.setVisibility(View.GONE);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** Domain yang boleh dibuka di dalam aplikasi; selain itu dibuka di browser/Drive. */
    private static boolean isInternalHost(String host) {
        if (host == null) return false;
        return host.equals("script.google.com") || host.endsWith(".googleusercontent.com")
                || host.equals("accounts.google.com") || host.endsWith(".script.google.com");
    }

    private void openExternal(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show(); }
    }

    // ------------------------------------------------------------------
    private class AppWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) return false;
            if (!"https".equals(scheme) && !"http".equals(scheme)) { openExternal(uri); return true; }
            if (isInternalHost(uri.getHost())) return false;
            openExternal(uri);
            return true;
        }
        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { progress.setVisibility(View.VISIBLE); }
        @Override public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE); swipe.setRefreshing(false);
            if (url != null && url.contains("script.google.com")) view.evaluateJavascript(FIT_SCRIPT, null);
        }
        @Override public void onPageCommitVisible(WebView view, String url) {
            if (url != null && url.contains("script.google.com")) view.evaluateJavascript(FIT_SCRIPT, null);
        }
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showOffline();
        }
    }

    private class AppChromeClient extends WebChromeClient {
        // GPS untuk absen
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) { callback.invoke(origin, true, false); return; }
            geoOrigin = origin; geoCallback = callback;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM_LOCATION);
        }

        // Kamera via getUserMedia (modal kamera internal)
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                if (hasPerm(Manifest.permission.CAMERA)) { request.grant(request.getResources()); return; }
                pendingWebPermission = request;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA);
            });
        }

        // <input type="file"> : foto absen (kamera), foto profil, surat dokter
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            pendingChooserParams = params;
            if (wantsCamera(params) && !hasPerm(Manifest.permission.CAMERA)) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA);
                return true;
            }
            launchChooser(params);
            return true;
        }

        // window.open (Drive, PDF, pengumuman) -> buka di aplikasi luar
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView temp = new WebView(MainActivity.this);
            temp.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    openExternal(r.getUrl()); v.destroy(); return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(temp);
            resultMsg.sendToTarget();
            return true;
        }

        @Override public void onProgressChanged(WebView view, int p) { if (p >= 100) progress.setVisibility(View.GONE); }
    }

    private boolean wantsCamera(WebChromeClient.FileChooserParams p) {
        if (p == null) return false;
        if (p.isCaptureEnabled()) return true;
        for (String t : p.getAcceptTypes()) if (t != null && t.startsWith("image")) return true;
        return false;
    }

    private void launchChooser(WebChromeClient.FileChooserParams params) {
        Intent cameraIntent = null;
        if (wantsCamera(params) && hasPerm(Manifest.permission.CAMERA)) {
            try {
                File photo = File.createTempFile("absen_", ".jpg", getCacheDir());
                cameraPhotoUri = FileProvider.getUriForFile(this, "com.ideacreative.hrga.fileprovider", photo);
                cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                cameraIntent.putExtra("android.intent.extras.CAMERA_FACING", 1);          // minta kamera depan
                cameraIntent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
                cameraIntent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
                cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) { cameraIntent = null; }
        }

        Intent target;
        if (params != null && params.isCaptureEnabled() && cameraIntent != null) {
            target = cameraIntent; // Absen: wajib langsung kamera, tidak boleh pilih galeri
        } else {
            Intent pick = params != null ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT).setType("*/*");
            target = Intent.createChooser(pick, "Pilih File");
            if (cameraIntent != null) target.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
        }
        try { startActivityForResult(target, REQ_FILE); }
        catch (ActivityNotFoundException e) { cancelFileChooser(); }
    }

    private void cancelFileChooser() {
        if (filePathCallback != null) { filePathCallback.onReceiveValue(null); filePathCallback = null; }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) results = new Uri[]{data.getData()};
            else if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (cameraPhotoUri != null) results = new Uri[]{cameraPhotoUri};
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraPhotoUri = null;
    }

    private boolean hasPerm(String p) { return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED; }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_PERM_LOCATION && geoCallback != null) {
            geoCallback.invoke(geoOrigin, granted, false);
            if (!granted) Toast.makeText(this, "Izin lokasi dibutuhkan untuk absen", Toast.LENGTH_LONG).show();
            geoCallback = null;
        } else if (requestCode == REQ_PERM_CAMERA) {
            if (pendingWebPermission != null) {
                if (granted) pendingWebPermission.grant(pendingWebPermission.getResources()); else pendingWebPermission.deny();
                pendingWebPermission = null;
            }
            if (filePathCallback != null) {
                if (granted) launchChooser(pendingChooserParams);
                else { Toast.makeText(this, "Izin kamera dibutuhkan untuk foto absen", Toast.LENGTH_LONG).show(); cancelFileChooser(); }
            }
        }
    }

    // Tombol Back: dulu ke riwayat halaman (Home), baru keluar aplikasi
    @Override
    public void onBackPressed() {
        if (!showingOffline && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); webView.saveState(out); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onPause() { webView.onPause(); CookieManager.getInstance().flush(); super.onPause(); }

    // ------------------------------------------------------------------
    /** Jembatan JS -> Android (dipakai Index.html bila berjalan di aplikasi). */
    public class AndroidBridge {
        @JavascriptInterface public boolean isApp() { return true; }

        @JavascriptInterface public void retry() { runOnUiThread(MainActivity.this::loadApp); }

        /** Simpan file (mis. slip gaji PDF) lalu tampilkan pilihan buka/bagikan. */
        @JavascriptInterface
        public void saveFile(String dataUrlOrBase64, String fileName, String mime) {
            try {
                String b64 = dataUrlOrBase64.contains(",") ? dataUrlOrBase64.substring(dataUrlOrBase64.indexOf(',') + 1) : dataUrlOrBase64;
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
                File out = new File(getCacheDir(), safeName);
                try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, "com.ideacreative.hrga.fileprovider", out);
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent share = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(share, "Simpan / Bagikan " + safeName);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{view});
                runOnUiThread(() -> startActivity(chooser));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Gagal menyimpan file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }
}
