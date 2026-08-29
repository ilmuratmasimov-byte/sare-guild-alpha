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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
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

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private DownloadManager downloadManager;
    private SharedPreferences preferences;
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
                connection = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "application/json");

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

    public final class SareBridge {
        @JavascriptInterface
        public void checkForUpdates() {
            MainActivity.this.checkForUpdates();
        }

        @JavascriptInterface
        public String getAppVersion() {
            return BuildConfig.VERSION_NAME;
        }
    }
}

