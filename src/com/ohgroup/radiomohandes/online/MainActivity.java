package com.ohgroup.radiomohandes.online;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** A live website wrapper: no packaged web copy, native JS bridge or metadata gate. */
public final class MainActivity extends Activity {
    private static final String APP_URL = "https://engomid.com/radio/";
    private static final int FILE_REQUEST = 51;
    private static final int STORAGE_REQUEST = 52;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private FrameLayout root;
    private LinearLayout messagePanel;
    private TextView message;
    private ProgressBar spinner;
    private Button retry;
    private boolean ready, pageFailed, destroyed;
    private ValueCallback<Uri[]> fileCallback;
    private String[] pendingDownload;
    private final Runnable loadTimeout = () -> { if (!ready && !destroyed) showError("دریافت سایت طول کشید. اتصال اینترنت را بررسی کنید و دوباره تلاش کنید."); };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        int barFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) barFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(barFlags);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        root.addView(web, new FrameLayout.LayoutParams(-1,-1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // ACTION_OPEN_DOCUMENT uploads; URL navigation remains restricted.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString()+" RadioMohandesApp/1.0.36");
        settings.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if(fileCallback!=null) fileCallback.onReceiveValue(null);
                fileCallback=callback;
                try { startActivityForResult(params.createIntent(),FILE_REQUEST); }
                catch(ActivityNotFoundException error) { fileCallback.onReceiveValue(null); fileCallback=null; return false; }
                return true;
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message result) {
                if(!userGesture) return false;
                final WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient(){
                    private boolean route(Uri uri) {
                        if(isAppUrl(uri)) web.loadUrl(uri.toString()); else openExternal(uri);
                        handler.post(popup::destroy);
                        return true;
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return route(r.getUrl()); }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, String u) { return route(Uri.parse(u)); }
                });
                ((WebView.WebViewTransport)result.obj).setWebView(popup);
                result.sendToTarget();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return request.isForMainFrame() && navigate(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return navigate(Uri.parse(url)); }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) { pageFailed=false; }
            @Override public void onPageCommitVisible(WebView v, String url) { if(!pageFailed && isAppUrl(Uri.parse(url))) showWeb(); }
            @Override public void onPageFinished(WebView v, String url) {
                if(pageFailed || !isAppUrl(Uri.parse(url))) return;
                showWeb();
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if(request.isForMainFrame()) { pageFailed=true; showError("ارتباط با رادیو مهندس برقرار نشد.\nاتصال اینترنت را بررسی کنید و دوباره تلاش کنید."); }
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
                if(request.isForMainFrame() && response.getStatusCode()>=400) { pageFailed=true; showError("سایت در حال حاضر در دسترس نیست. کمی بعد دوباره تلاش کنید."); }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler ssl, SslError error) {
                ssl.cancel(); // Never bypass certificate validation.
                if (isAppUrl(Uri.parse(error.getUrl()))) { pageFailed=true; showError("اتصال امن به سایت برقرار نشد. تاریخ دستگاه و اتصال اینترنت را بررسی کنید."); }
            }
        });
        web.setDownloadListener((url,ua,disposition,type,length)->requestDownload(url,ua,disposition,type));
        createMessagePanel();
        setContentView(root);
        if(saved!=null && web.restoreState(saved)!=null) {
            handler.postDelayed(loadTimeout,30000);
        } else openSite();
    }
    private void showWeb() {
        ready=true; handler.removeCallbacks(loadTimeout);
        messagePanel.setVisibility(View.GONE); web.setVisibility(View.VISIBLE);
    }
    private void createMessagePanel() {
        messagePanel=new LinearLayout(this);
        messagePanel.setOrientation(LinearLayout.VERTICAL);
        messagePanel.setGravity(Gravity.CENTER);
        messagePanel.setPadding(dp(30),dp(30),dp(30),dp(30));
        messagePanel.setBackgroundColor(Color.WHITE);
        ImageView logo=new ImageView(this);
        logo.setImageResource(R.drawable.brand_logo);
        logo.setContentDescription("رادیو مهندس");
        messagePanel.addView(logo,new LinearLayout.LayoutParams(dp(112),dp(112)));
        TextView title=new TextView(this);title.setText("رادیو مهندس");title.setTextSize(24);title.setTextColor(Color.rgb(34,43,54));
        LinearLayout.LayoutParams titleLayout=new LinearLayout.LayoutParams(-2,-2);titleLayout.topMargin=dp(18);
        messagePanel.addView(title,titleLayout);
        message=new TextView(this);message.setTextSize(15);message.setGravity(Gravity.CENTER);message.setTextColor(Color.rgb(96,107,120));
        message.setText("در حال دریافت رادیو مهندس…");
        LinearLayout.LayoutParams textLayout=new LinearLayout.LayoutParams(-1,-2);textLayout.topMargin=dp(18);textLayout.bottomMargin=dp(18);
        messagePanel.addView(message,textLayout);
        spinner=new ProgressBar(this);spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255,145,77)));
        messagePanel.addView(spinner,new LinearLayout.LayoutParams(dp(32),dp(32)));
        retry=new Button(this);retry.setText("تلاش دوباره");retry.setTextColor(Color.rgb(34,43,54));
        retry.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255,145,77)));
        retry.setOnClickListener(v->openSite());retry.setVisibility(View.GONE);
        messagePanel.addView(retry,new LinearLayout.LayoutParams(-2,-2));
        root.addView(messagePanel,new FrameLayout.LayoutParams(-1,-1));
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private boolean isAppUrl(Uri uri) {
        String host=uri.getHost(),path=uri.getPath();
        return "https".equalsIgnoreCase(uri.getScheme()) && ("engomid.com".equalsIgnoreCase(host)||"www.engomid.com".equalsIgnoreCase(host))
            && (uri.getPort()==-1 || uri.getPort()==443) && path!=null && (path.equals("/radio") || path.startsWith("/radio/"));
    }
    private boolean navigate(Uri uri) { if(isAppUrl(uri))return false;openExternal(uri);return true; }
    private void openExternal(Uri uri) {
        String scheme=uri.getScheme();
        if(!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)||"mailto".equalsIgnoreCase(scheme)||"tel".equalsIgnoreCase(scheme)||"tg".equalsIgnoreCase(scheme)))return;
        try {startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));}
        catch(ActivityNotFoundException error){Toast.makeText(this,"برنامه‌ای برای بازکردن این پیوند پیدا نشد.",Toast.LENGTH_SHORT).show();}
    }
    private void requestDownload(String url,String ua,String disposition,String type) {
        if(!"https".equalsIgnoreCase(Uri.parse(url).getScheme())) { Toast.makeText(this,"این فایل را از نسخه وب دانلود کنید.",Toast.LENGTH_LONG).show();return; }
        if(Build.VERSION.SDK_INT<=28 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingDownload=new String[]{url,ua,disposition,type};
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_REQUEST);return;
        }
        download(url,ua,disposition,type);
    }
    private void download(String url,String ua,String disposition,String type) {
        try {
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
            String name=URLUtil.guessFileName(url,disposition,type).replaceAll("[\\\\/:*?\"<>|]","_");
            request.setTitle(name).setDescription("رادیو مهندس").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
            if(type!=null)request.setMimeType(type);
            if(ua!=null)request.addRequestHeader("User-Agent",ua);
            // Cookies come from the destination origin, never from another domain.
            String cookie=CookieManager.getInstance().getCookie(url);
            if(cookie!=null)request.addRequestHeader("Cookie",cookie);
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this,"دانلود آغاز شد؛ فایل در پوشه دانلودها ذخیره می‌شود.",Toast.LENGTH_LONG).show();
        } catch(Exception error) { Toast.makeText(this,"دانلود انجام نشد. از نسخه وب تلاش کنید.",Toast.LENGTH_LONG).show(); }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==STORAGE_REQUEST && pendingDownload!=null) {
            String[] d=pendingDownload;pendingDownload=null;
            if(grants.length>0 && grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)download(d[0],d[1],d[2],d[3]);
            else Toast.makeText(this,"برای ذخیره فایل در این نسخه اندروید، دسترسی ذخیره‌سازی لازم است.",Toast.LENGTH_LONG).show();
        }
    }
    private void showError(String text) {
        if(destroyed)return;
        ready=false;handler.removeCallbacks(loadTimeout);web.setVisibility(View.INVISIBLE);messagePanel.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);retry.setVisibility(View.VISIBLE);message.setText(text);
    }
    private void openSite() {
        if(destroyed)return;
        ready=false;pageFailed=false;messagePanel.setVisibility(View.VISIBLE);web.setVisibility(View.INVISIBLE);
        spinner.setVisibility(View.VISIBLE);retry.setVisibility(View.GONE);message.setText("در حال دریافت رادیو مهندس…");
        handler.removeCallbacks(loadTimeout);handler.postDelayed(loadTimeout,30000);
        web.loadUrl(APP_URL);
    }
    @Override public void onResume(){super.onResume();if(web!=null){web.onResume();if(ready)web.evaluateJavascript("if(window.RadioRefreshContent)window.RadioRefreshContent();if(navigator.serviceWorker)navigator.serviceWorker.getRegistration().then(function(r){if(r)r.update()}).catch(function(){});",null);}}
    @Override protected void onSaveInstanceState(Bundle state){if(web!=null)web.saveState(state);super.onSaveInstanceState(state);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==FILE_REQUEST && fileCallback!=null){fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));fileCallback=null;}}
    @Override public void onBackPressed(){
        if(!ready){super.onBackPressed();return;}
        web.evaluateJavascript("!!(window.RadioNativeBack&&window.RadioNativeBack())",value->{if(!"true".equals(value)){if(web.canGoBack())web.goBack();else finish();}});
    }
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}if(web!=null){root.removeView(web);web.destroy();}super.onDestroy();}
}
