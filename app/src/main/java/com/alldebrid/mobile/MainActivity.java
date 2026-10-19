package com.alldebrid.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final String BG = "#0b1020";

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private String pendingIncoming = null;
    private boolean pageReady = false;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor(BG));
        w.setNavigationBarColor(Color.parseColor(BG));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(BG));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        if (Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_OFF);   // the page handles its own dark/light theme

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:///android_asset/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                deliverIncoming();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose file"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        handleIntent(getIntent());
        web.loadUrl("file:///android_asset/index.html");
    }

    // ---------- Text received from Share / magnet: links ----------
    private void handleIntent(Intent i) {
        if (i == null) return;
        String text = null;
        String a = i.getAction();
        if (Intent.ACTION_VIEW.equals(a) && i.getData() != null) text = i.getDataString();
        else if (Intent.ACTION_SEND.equals(a)) text = i.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && !text.isEmpty()) {
            pendingIncoming = text;
            deliverIncoming();
        }
    }

    private void deliverIncoming() {
        if (pendingIncoming != null && pageReady && web != null) {
            final String t = pendingIncoming;
            pendingIncoming = null;
            web.post(() -> web.evaluateJavascript(
                    "window.__incoming && window.__incoming(" + JSONObject.quote(t) + ")", null));
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            Uri[] result = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int k = 0; k < n; k++) result[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCb.onReceiveValue(result);
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back ? window.__back() : false)", v -> {
            if (!"true".equals(v)) runOnUiThread(this::superBack);
        });
    }

    private void superBack() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    // ---------- Theme (status / navigation bar colours) ----------
    private void applyBars(boolean dark) {
        int c = Color.parseColor(dark ? "#0b1020" : "#f3f5fb");
        Window w = getWindow();
        w.setStatusBarColor(c);
        w.setNavigationBarColor(c);
        if (web != null) web.setBackgroundColor(c);
        int flags = dark ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        w.getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (web != null) web.evaluateJavascript("window.__sysTheme && window.__sysTheme()", null);
    }

    // ---------- helpers ----------
    private void sendToJs(String id, JSONObject res) {
        final String call = "window.__cb(" + JSONObject.quote(id) + "," + JSONObject.quote(res.toString()) + ")";
        runOnUiThread(() -> web.evaluateJavascript(call, null));
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void applyHeaders(HttpURLConnection c, String headers) throws Exception {
        JSONObject h = new JSONObject(headers == null || headers.isEmpty() ? "{}" : headers);
        Iterator<String> it = h.keys();
        while (it.hasNext()) {
            String k = it.next();
            c.setRequestProperty(k, h.getString(k));
        }
    }

    private static String cleanSeg(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (r.startsWith(".")) r = "_" + r.substring(1);
        return r.isEmpty() ? "_" : r;
    }

    private static String cleanPath(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals("..") || part.equals(".")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(cleanSeg(part));
        }
        return sb.toString();
    }

    // ---------- JavaScript Bridge ----------
    public class Bridge {

        @JavascriptInterface
        public void request(final String id, final String method, final String url,
                            final String headers, final String body) {
            pool.execute(() -> {
                JSONObject out = new JSONObject();
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(90000);
                    c.setRequestProperty("User-Agent", "AllDebridMobile/1.0");
                    applyHeaders(c, headers);
                    if ("POST".equals(method)) {
                        byte[] data = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(data.length);
                        OutputStream os = c.getOutputStream();
                        os.write(data);
                        os.close();
                    }
                    int code = c.getResponseCode();
                    InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    out.put("status", code);
                    out.put("body", readAll(is));
                } catch (Exception e) {
                    try { out.put("error", e.getMessage() == null ? e.toString() : e.getMessage()); }
                    catch (Exception ignored) { }
                } finally {
                    if (c != null) c.disconnect();
                }
                sendToJs(id, out);
            });
        }

        @JavascriptInterface
        public void upload(final String id, final String url, final String headers, final String filesJson) {
            pool.execute(() -> {
                JSONObject out = new JSONObject();
                HttpURLConnection c = null;
                try {
                    String boundary = "----ADBoundary" + System.currentTimeMillis();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    JSONArray arr = new JSONArray(filesJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject f = arr.getJSONObject(i);
                        String name = f.getString("name").replace("\"", "_");
                        byte[] bytes = Base64.decode(f.getString("b64"), Base64.DEFAULT);
                        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                        bos.write(("Content-Disposition: form-data; name=\"files[]\"; filename=\"" + name + "\"\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        bos.write("Content-Type: application/x-bittorrent\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                        bos.write(bytes);
                        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                    bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    byte[] data = bos.toByteArray();

                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(120000);
                    c.setDoOutput(true);
                    c.setRequestProperty("User-Agent", "AllDebridMobile/1.0");
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    applyHeaders(c, headers);
                    c.setFixedLengthStreamingMode(data.length);
                    OutputStream os = c.getOutputStream();
                    os.write(data);
                    os.close();

                    int code = c.getResponseCode();
                    InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    out.put("status", code);
                    out.put("body", readAll(is));
                } catch (Exception e) {
                    try { out.put("error", e.getMessage() == null ? e.toString() : e.getMessage()); }
                    catch (Exception ignored) { }
                } finally {
                    if (c != null) c.disconnect();
                }
                sendToJs(id, out);
            });
        }

        @JavascriptInterface
        public long download(String url, String filename, String subdir) {
            try {
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String fname = cleanSeg(filename);
                r.setTitle(fname);
                r.setDescription("AllDebrid");
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setAllowedOverMetered(true);
                r.setAllowedOverRoaming(true);
                String sub = (subdir == null || subdir.isEmpty()) ? "" : cleanPath(subdir) + "/";
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AllDebrid/" + sub + fname);
                return dm.enqueue(r);
            } catch (Exception e) {
                return -1;
            }
        }

        @JavascriptInterface
        public String downloadStatus(String idsJson) {
            JSONArray out = new JSONArray();
            try {
                JSONArray ids = new JSONArray(idsJson);
                long[] arr = new long[ids.length()];
                for (int i = 0; i < arr.length; i++) arr[i] = ids.getLong(i);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                Cursor c = dm.query(new DownloadManager.Query().setFilterById(arr));
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            JSONObject o = new JSONObject();
                            o.put("id", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)));
                            o.put("status", c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)));
                            o.put("done", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                            o.put("total", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                            o.put("reason", c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
                            out.put(o);
                        }
                    } finally {
                        c.close();
                    }
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        @JavascriptInterface
        public void cancelDownload(long id) {
            try {
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.remove(id);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean isSystemDark() {
            return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface
        public void setTheme(final boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }

        @JavascriptInterface
        public void play(final String itemsJson, final int start, final String apiKey) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                    i.putExtra("items", itemsJson);
                    i.putExtra("start", start);
                    i.putExtra("key", apiKey);
                    startActivity(i);
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void openDownloads() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
                catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void copy(final String t) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("AllDebrid", t));
            });
        }

        @JavascriptInterface
        public String paste() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                    return cs == null ? "" : cs.toString();
                }
            } catch (Exception ignored) { }
            return "";
        }

        @JavascriptInterface
        public void openUrl(final String u) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
                catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void share(final String text, final String title) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, title));
                } catch (Exception ignored) { }
            });
        }
    }
}
