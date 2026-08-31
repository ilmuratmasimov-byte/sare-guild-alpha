package kz.sare.guild;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
    private static final int NOTIFICATION_PERMISSION_REQUEST = 4302;
    private static final String AUTH_PREFS = "sare_auth";
    private static final String CHAT_CHANNEL_ID = "sare_guild_chat";

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
        createChatNotificationChannel();

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
                            "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show();
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
                "Проверяем обновления…", Toast.LENGTH_SHORT).show());
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
                    throw new IllegalStateException("Сервер обновлений ответил кодом " + status);
                }

                JSONObject payload = new JSONObject(readFully(connection.getInputStream()));
                int remoteVersionCode = payload.getInt("versionCode");
                String remoteVersionName = payload.getString("versionName");
                String apkUrl = payload.getString("apkUrl");

                if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
                    runOnUiThread(() -> showMessage("Обновления",
                            "Установлена актуальная версия " + BuildConfig.VERSION_NAME + "."));
                    return;
                }

                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Доступна версия " + remoteVersionName)
                        .setMessage("Скачать обновление? Android отдельно попросит подтвердить установку.")
                        .setNegativeButton("Позже", null)
                        .setPositiveButton("Скачать", (dialog, which) ->
                                downloadApk(apkUrl, remoteVersionName))
                        .show());
            } catch (Exception error) {
                runOnUiThread(() -> showMessage("Не удалось проверить обновления",
                        error.getMessage() == null ? "Проверьте интернет-соединение." : error.getMessage()));
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
            sendAuthResult(false, action, "Авторизация Firebase ещё не настроена для этой сборки.", null);
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
                    resolvedName = authPreferences.getString("display_name", "Авантюрист");
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
                        error.getMessage() == null ? "Не удалось связаться с Firebase." : error.getMessage(), null);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void sendPasswordReset(String email) {
        if (BuildConfig.FIREBASE_API_KEY.isEmpty()) {
            sendAuthResult(false, "reset", "Авторизация Firebase ещё не настроена для этой сборки.", null);
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
                sendAuthResult(true, "reset", "Письмо для восстановления отправлено.", null);
            } catch (Exception error) {
                sendAuthResult(false, "reset",
                        error.getMessage() == null ? "Не удалось отправить письмо." : error.getMessage(), null);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private String firebaseErrorMessage(JSONObject response) {
        String code = response.optJSONObject("error") == null ? "" :
                response.optJSONObject("error").optString("message");
        if (code.startsWith("EMAIL_EXISTS")) return "Этот email уже зарегистрирован.";
        if (code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD"))
            return "Неверный email или пароль.";
        if (code.startsWith("EMAIL_NOT_FOUND")) return "Пользователь с таким email не найден.";
        if (code.startsWith("WEAK_PASSWORD")) return "Пароль должен содержать не менее 6 символов.";
        if (code.startsWith("INVALID_EMAIL")) return "Введите корректный email.";
        if (code.startsWith("TOO_MANY_ATTEMPTS")) return "Слишком много попыток. Попробуйте позже.";
        if (code.startsWith("OPERATION_NOT_ALLOWED")) return "Вход по email ещё не включён в Firebase.";
        return code.isEmpty() ? "Ошибка Firebase." : code;
    }

    private JSONObject currentUserJson() throws Exception {
        String email = authPreferences.getString("email", "");
        if (email.isEmpty()) return null;
        String displayName = authPreferences.getString("display_name", "").trim();
        if (displayName.isEmpty() || displayName.indexOf('\uFFFD') >= 0) {
            int at = email.indexOf('@');
            displayName = at > 0 ? email.substring(0, at) : "Авантюрист";
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
                throw new IllegalArgumentException("Недопустимый путь базы данных.");
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
                    throw new IllegalArgumentException("Недопустимая операция базы данных.");
                }
                String token = authPreferences.getString("id_token", "");
                if (token.isEmpty()) throw new IllegalStateException("Войдите в аккаунт повторно.");
                JSONObject result = performCloudRequest(normalizedMethod, path, jsonBody, token);
                if (result.getInt("status") == 401 && refreshIdToken()) {
                    token = authPreferences.getString("id_token", "");
                    result = performCloudRequest(normalizedMethod, path, jsonBody, token);
                }
                int status = result.getInt("status");
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException(status == 401 || status == 403
                            ? "Недостаточно прав для этой операции."
                            : "Firebase ответил кодом " + status + ".");
                }
                sendCloudResult(requestId, true, result.getString("body"), "");
            } catch (Exception error) {
                sendCloudResult(requestId, false, "null",
                        error.getMessage() == null ? "Не удалось связаться с общей базой." : error.getMessage());
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
                        .put("message", "Некорректный ответ общей базы.");
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
            showMessage("Ошибка обновления", "Получен недопустимый адрес APK.");
            return;
        }

        String fileName = "SARE-Guild-" + versionName.replaceAll("[^0-9A-Za-z._-]", "_") + ".apk";
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle("Гильдия SARE " + versionName)
                .setDescription("Загрузка обновления")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

        long downloadId = downloadManager.enqueue(request);
        preferences.edit().putLong(PENDING_DOWNLOAD_ID, downloadId).apply();
        Toast.makeText(this, "Обновление загружается", Toast.LENGTH_LONG).show();
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
            showMessage("Разрешите установку обновлений",
                    "Включите «Установка неизвестных приложений» для SARE и вернитесь в приложение.");
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

    private void createChatNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHAT_CHANNEL_ID,
                "Сообщения Гильдии",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Новые сообщения в чатах SARE Guild");
        manager.createNotificationChannel(channel);
    }

    private void requestChatNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void showChatNotification(String title, String message, String chatId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                Math.abs((chatId == null ? "general" : chatId).hashCode()),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHAT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title == null ? "SARE Guild" : title)
                .setContentText(message == null ? "Новое сообщение" : message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(6000 + Math.abs((chatId == null ? "general" : chatId).hashCode() % 1000), notification);
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
                sendAuthResult(false, "profile", "Введите корректное имя.", null);
                return;
            }
            authPreferences.edit().putString("display_name", cleanName).apply();
            try {
                sendAuthResult(true, "profile", "Имя сохранено.", currentUserJson());
            } catch (Exception error) {
                sendAuthResult(false, "profile", "Не удалось сохранить имя.", null);
            }
        }

        @JavascriptInterface
        public void cloudRequest(String requestId, String method, String path, String jsonBody) {
            MainActivity.this.cloudRequest(requestId, method, path, jsonBody);
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(MainActivity.this::requestChatNotificationPermission);
        }

        @JavascriptInterface
        public void showChatNotification(String title, String message, String chatId) {
            runOnUiThread(() -> MainActivity.this.showChatNotification(title, message, chatId));
        }

        @JavascriptInterface
        public void signOut() {
            authPreferences.edit().clear().apply();
            sendAuthResult(true, "logout", "", null);
        }
    }
}

