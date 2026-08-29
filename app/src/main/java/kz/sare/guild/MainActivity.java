package kz.sare.guild;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String UPDATE_URL =
            "https://github.com/ilmuratmasimov-byte/sare-guild-alpha/releases/latest/download/version.json";
    private static final String PREFS = "sare_updates";
    private static final String PENDING_DOWNLOAD_ID = "pending_download_id";
    private static final int FILE_CHOOSER_REQUEST = 4301;
    private static final String AUTH_PREFS = "sare_auth";

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private DownloadManager downloadManager;
    private SharedPreferences preferences;
    private SharedPreferences authPreferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            long pendingId = preferences.getLong(PENDING_DOWNLOAD_ID, -1L);
            if (completedId == pendingId) {
                promptApkInstall(completedId);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        authPreferences = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);

        webView = new WebView(this);
        configureWebView();
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new SareBridge(), "SareAndroid");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception error) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this,
                            "�� 㤠���� ������ �롮� 䠩��", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        long pendingId = preferences.getLong(PENDING_DOWNLOAD_ID, -1L);
        if (pendingId > 0 && canInstallPackages()) {
            promptApkInstall(pendingId);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(downloadReceiver);
        executor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("SareAndroid");
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void checkForUpdates() {
        runOnUiThread(() -> Toast.makeText(this,
                "�஢��塞 ����������:", Toast.LENGTH_SHORT).show());
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String uncachedUpdateUrl = UPDATE_URL + "?t=" + System.currentTimeMillis();
                connection = (HttpURLConnection) new URL(uncachedUpdateUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
                connection.setRequestProperty("Pragma", "no-cache");

                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("��ࢥ� ���������� �⢥⨫ ����� " + status);
                }

                JSONObject payload = new JSONObject(readFully(connection.getInputStream()));
                int remoteVersionCode = payload.getInt("versionCode");
                String remoteVersionName = payload.getString("versionName");
                String apkUrl = payload.getString("apkUrl");

                if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
                    runOnUiThread(() -> showMessage("����������",
                            "��⠭������ ���㠫쭠� ����� " + BuildConfig.VERSION_NAME + "."));
                    return;
                }

                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("����㯭� ����� " + remoteVersionName)
                        .setMessage("������ ����������? Android �⤥�쭮 ������ ���⢥न�� ��⠭����.")
                        .setNegativeButton("�����", null)
                        .setPositiveButton("������", (dialog, which) ->
                                downloadApk(apkUrl, remoteVersionName))
                        .show());
            } catch (Exception error) {
                runOnUiThread(() -> showMessage("�� 㤠���� �஢���� ����������",
                        error.getMessage() == null ? "�஢���� ���୥�-ᮥ�������." : error.getMessage()));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private String readFully(InputStream input) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private void authenticate(String action, String email, String password, String displayName) {
        if (BuildConfig.FIREBASE_API_KEY.isEmpty()) {
            sendAuthResult(false, action, "���ਧ��� Firebase ��� �� ����஥�� ��� �⮩ ᡮન.", null);
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String endpoint = "register".equals(action) ? "accounts:signUp" : "accounts:signInWithPassword";
                URL url = new URL("https://identitytoolkit.googleapis.com/v1/" + endpoint
                        + "?key=" + BuildConfig.FIREBASE_API_KEY);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                JSONObject request = new JSONObject()
                        .put("email", email.trim())
                        .put("password", password)
                        .put("returnSecureToken", true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(request.toString().getBytes(StandardCharsets.UTF_8));
                }

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                JSONObject response = new JSONObject(readFully(stream));
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException(firebaseErrorMessage(response));
                }

                String resolvedName = displayName == null ? "" : displayName.trim();
                if (resolvedName.isEmpty()) {
                    resolvedName = authPreferences.getString("display_name", "���������");
                }
                authPreferences.edit()
                        .putString("email", response.optString("email", email.trim()))
                        .putString("display_name", resolvedName)
                        .putString("local_id", response.optString("localId"))
                        .putString("id_token", response.optString("idToken"))
                        .putString("refresh_token", response.optString("refreshToken"))
                        .apply();
                sendAuthResult(true, action, "", currentUserJson());
            } catch (Exception error) {
                sendAuthResult(false, action,
                        error.getMessage() == null ? "�� 㤠���� �易���� � Firebase." : error.getMessage(), null);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void sendPasswordReset(String email) {
        if (BuildConfig.FIREBASE_API_KEY.isEmpty()) {
            sendAuthResult(false, "reset", "���ਧ��� Firebase ��� �� ����஥�� ��� �⮩ ᡮન.", null);
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key="
                        + BuildConfig.FIREBASE_API_KEY);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                JSONObject request = new JSONObject().put("requestType", "PASSWORD_RESET")
                        .put("email", email.trim());
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(request.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                JSONObject response = new JSONObject(readFully(stream));
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException(firebaseErrorMessage(response));
                }
                sendAuthResult(true, "reset", "���쬮 ��� ����⠭������� ��ࠢ����.", null);
            } catch (Exception error) {
                sendAuthResult(false, "reset",
                        error.getMessage() == null ? "�� 㤠���� ��ࠢ��� ���쬮." : error.getMessage(), null);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private String firebaseErrorMessage(JSONObject response) {
        String code = response.optJSONObject("error") == null ? "" :
                response.optJSONObject("error").optString("message");
        if (code.startsWith("EMAIL_EXISTS")) return "��� email 㦥 ��ॣ����஢��.";
        if (code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD"))
            return "������ email ��� ��஫�.";
        if (code.startsWith("EMAIL_NOT_FOUND")) return "���짮��⥫� � ⠪�� email �� ������.";
        if (code.startsWith("WEAK_PASSWORD")) return "��஫� ������ ᮤ�ঠ�� �� ����� 6 ᨬ�����.";
        if (code.startsWith("INVALID_EMAIL")) return "������ ���४�� email.";
        if (code.startsWith("TOO_MANY_ATTEMPTS")) return "���誮� ����� ����⮪. ���஡�� �����.";
        if (code.startsWith("OPERATION_NOT_ALLOWED")) return "�室 �� email ��� �� ������ � Firebase.";
        return code.isEmpty() ? "�訡�� Firebase." : code;
    }

    private JSONObject currentUserJson() throws Exception {
        String email = authPreferences.getString("email", "");
        if (email.isEmpty()) return null;
        String displayName = authPreferences.getString("display_name", "").trim();
        if (displayName.isEmpty() || displayName.indexOf('\uFFFD') >= 0) {
            int at = email.indexOf('@');
            displayName = at > 0 ? email.substring(0, at) : "���������";
            authPreferences.edit().putString("display_name", displayName).apply();
        }
        return new JSONObject()
                .put("email", email)
                .put("displayName", displayName)
                .put("localId", authPreferences.getString("local_id", ""));
    }

    private String encodeDatabasePath(String path) throws Exception {
        String clean = path == null ? "" : path.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) return "";
        StringBuilder encoded = new StringBuilder();
        for (String segment : clean.split("/")) {
            if (!segment.matches("[A-Za-z0-9_-]{1,180}")) {
                throw new IllegalArgumentException("�������⨬� ���� ���� ������.");
            }
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private JSONObject performCloudRequest(String method, String path, String jsonBody, String idToken)
            throws Exception {
        String encodedPath = encodeDatabasePath(path);
        String endpoint = BuildConfig.FIREBASE_DATABASE_URL + "/" + encodedPath + ".json?auth="
                + URLEncoder.encode(idToken, StandardCharsets.UTF_8.name());
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (!"GET".equals(method) && !"DELETE".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write((jsonBody == null || jsonBody.isEmpty() ? "null" : jsonBody)
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "null" : readFully(stream);
            return new JSONObject().put("status", status).put("body", body.isEmpty() ? "null" : body);
        } finally {
            connection.disconnect();
        }
    }

    private boolean refreshIdToken() {
        HttpURLConnection connection = null;
        try {
            String refreshToken = authPreferences.getString("refresh_token", "");
            if (refreshToken.isEmpty()) return false;
            URL url = new URL("https://securetoken.googleapis.com/v1/token?key="
                    + BuildConfig.FIREBASE_API_KEY);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            String body = "grant_type=refresh_token&refresh_token="
                    + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8.name());
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return false;
            JSONObject response = new JSONObject(readFully(connection.getInputStream()));
            authPreferences.edit()
                    .putString("id_token", response.optString("id_token"))
                    .putString("refresh_token", response.optString("refresh_token", refreshToken))
                    .apply();
            return !response.optString("id_token").isEmpty();
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void cloudRequest(String requestId, String method, String path, String jsonBody) {
        executor.execute(() -> {
            try {
                String normalizedMethod = method == null ? "" : method.toUpperCase();
                if (!(normalizedMethod.equals("GET") || normalizedMethod.equals("PUT")
                        || normalizedMethod.equals("PATCH") || normalizedMethod.equals("POST")
                        || normalizedMethod.equals("DELETE"))) {
                    throw new IllegalArgumentException("�������⨬�� ������ ���� ������.");
                }
                String token = authPreferences.getString("id_token", "");
                if (token.isEmpty()) throw new IllegalStateException("������ � ������ ����୮.");
                JSONObject result = performCloudRequest(normalizedMethod, path, jsonBody, token);
                if (result.getInt("status") == 401 && refreshIdToken()) {
                    token = authPreferences.getString("id_token", "");
                    result = performCloudRequest(normalizedMethod, path, jsonBody, token);
                }
                int status = result.getInt("status");
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException(status == 401 || status == 403
                            ? "�������筮 �ࠢ ��� �⮩ ����樨."
                            : "Firebase �⢥⨫ ����� " + status + ".");
                }
                sendCloudResult(requestId, true, result.getString("body"), "");
            } catch (Exception error) {
                sendCloudResult(requestId, false, "null",
                        error.getMessage() == null ? "�� 㤠���� �易���� � ��饩 �����." : error.getMessage());
            }
        });
    }

    private void sendCloudResult(String requestId, boolean ok, String rawData, String message) {
        try {
            JSONObject payload = new JSONObject().put("requestId", requestId).put("ok", ok)
                    .put("message", message == null ? "" : message);
            payload.put("data", ok ? new JSONTokener(rawData == null ? "null" : rawData).nextValue()
                    : JSONObject.NULL);
            String script = "window.SareCloud&&window.SareCloud.receive(" + payload + ")";
            runOnUiThread(() -> webView.evaluateJavascript(script, null));
        } catch (Exception error) {
            try {
                JSONObject fallback = new JSONObject().put("requestId", requestId).put("ok", false)
                        .put("message", "�����४�� �⢥� ��饩 ����.");
                runOnUiThread(() -> webView.evaluateJavascript(
                        "window.SareCloud&&window.SareCloud.receive(" + fallback + ")", null));
            } catch (Exception ignored) {
            }
        }
    }

    private void sendAuthResult(boolean ok, String action, String message, JSONObject user) {
        try {
            JSONObject payload = new JSONObject().put("ok", ok).put("action", action).put("message", message);
            if (user != null) payload.put("user", user);
            String script = "window.SareAuth&&window.SareAuth.receive(" + payload + ")";
            runOnUiThread(() -> webView.evaluateJavascript(script, null));
        } catch (Exception ignored) {
        }
    }

    private void downloadApk(String apkUrl, String versionName) {
        Uri uri = Uri.parse(apkUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                !"github.com".equalsIgnoreCase(uri.getHost())) {
            showMessage("�訡�� ����������", "����祭 �������⨬� ���� APK.");
            return;
        }

        String fileName = "SARE-Guild-" + versionName.replaceAll("[^0-9A-Za-z._-]", "_") + ".apk";
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle("���줨� SARE " + versionName)
                .setDescription("����㧪� ����������")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

        long downloadId = downloadManager.enqueue(request);
        preferences.edit().putLong(PENDING_DOWNLOAD_ID, downloadId).apply();
        Toast.makeText(this, "���������� ����㦠����", Toast.LENGTH_LONG).show();
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                getPackageManager().canRequestPackageInstalls();
    }

    private void promptApkInstall(long downloadId) {
        Uri apkUri = downloadManager.getUriForDownloadedFile(downloadId);
        if (apkUri == null) {
            return;
        }

        if (!canInstallPackages()) {
            showMessage("������ ��⠭���� ����������",
                    "������ <��⠭���� ���������� �ਫ������> ��� SARE � ��୨��� � �ਫ������.");
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            return;
        }

        preferences.edit().remove(PENDING_DOWNLOAD_ID).apply();
        Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(installIntent);
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    public final class SareBridge {
        @JavascriptInterface
        public void checkForUpdates() {
            MainActivity.this.checkForUpdates();
        }

        @JavascriptInterface
        public String getAppVersion() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public String getAuthState() {
            try {
                JSONObject user = currentUserJson();
                return new JSONObject().put("authenticated", user != null).put("user", user).toString();
            } catch (Exception error) {
                return "{\"authenticated\":false}";
            }
        }

        @JavascriptInterface
        public void signIn(String email, String password) {
            authenticate("login", email, password, null);
        }

        @JavascriptInterface
        public void register(String name, String email, String password) {
            authenticate("register", email, password, name);
        }

        @JavascriptInterface
        public void resetPassword(String email) {
            sendPasswordReset(email);
        }

        @JavascriptInterface
        public void setDisplayName(String displayName) {
            String cleanName = displayName == null ? "" : displayName.trim();
            if (cleanName.isEmpty() || cleanName.length() > 60 || cleanName.indexOf('\uFFFD') >= 0) {
                sendAuthResult(false, "profile", "������ ���४⭮� ���.", null);
                return;
            }
            authPreferences.edit().putString("display_name", cleanName).apply();
            try {
                sendAuthResult(true, "profile", "��� ��࠭���.", currentUserJson());
            } catch (Exception error) {
                sendAuthResult(false, "profile", "�� 㤠���� ��࠭��� ���.", null);
            }
        }

        @JavascriptInterface
        public void cloudRequest(String requestId, String method, String path, String jsonBody) {
            MainActivity.this.cloudRequest(requestId, method, path, jsonBody);
        }

        @JavascriptInterface
        public void signOut() {
            authPreferences.edit().clear().apply();
            sendAuthResult(true, "logout", "", null);
        }
    }
}

