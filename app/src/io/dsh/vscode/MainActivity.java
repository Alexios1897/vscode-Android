package io.dsh.vscode;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.net.Uri;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private WebView web;
    private TextView status;
    private ScrollView logView;
    private TextView logText;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean loaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 登录态依赖 cookie（vscode-cli-secret-half / vscode-secret-key-path），必须持久化
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        // 站外链接（GitHub 登录授权等）交给系统浏览器：不要让 WebView 离开 VS Code，
        // 否则页面被销毁，设备的登录轮询就断了。
        WebViewClient wc = new WebViewClient() {
            /**
             * 官方 CDN 上的中文消息表和本机构建对不上（25542 条 vs 28428 条，整表错位，
             * 于是界面里冒出 {0}/{1}）。这里用打包时按本机构建生成的对齐表顶替。
             */
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.contains("vscode-unpkg.net") && url.contains("/zh-cn/")) {
                    try {
                        return new WebResourceResponse("application/javascript", "UTF-8",
                                getAssets().open("nls.zh-cn.js"));
                    } catch (Throwable t) {
                        Boot.log("注入中文消息表失败: " + t);
                    }
                }
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return routeUrl(url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return routeUrl(req.getUrl().toString());
            }
        };
        web.setWebViewClient(wc);

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // window.open / target=_blank：同样甩给系统浏览器
                final WebView probe = new WebView(MainActivity.this);
                probe.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        routeUrl(url);
                        return true;
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        routeUrl(req.getUrl().toString());
                        return true;
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(probe);
                resultMsg.sendToTarget();
                return true;
            }
        });
        web.setBackgroundColor(Color.BLACK);
        cm.setAcceptThirdPartyCookies(web, true);
        web.clearCache(true);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(0xFFCCCCCC);
        status.setText("正在启动 VS Code…\n首次启动需要解压约 700MB，请稍候");
        status.setTextSize(16);
        status.setPadding(48, 120, 48, 48);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2));

        logView = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextColor(0xFFFFAAAA);
        logText.setTextSize(10);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setPadding(24, 24, 24, 24);
        logView.addView(logText);
        logView.setVisibility(View.GONE);
        root.addView(logView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        // 长按状态栏显示日志
        status.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // 令牌不进日志文件；需要在外部浏览器打开时，从这里手动复制完整地址
                logText.setText(Boot.webUrl() + "\n\n" + Boot.readLog(60000));
                logView.setVisibility(logView.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
                return true;
            }
        });

        startServer();
        waitForServer();
    }

    private void startServer() {
        Intent svc = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 通知栏点过「停止」之后又回到 App：重新拉起服务端
        if ("stopped".equals(Boot.state())) {
            loaded = false;
            status.setVisibility(View.VISIBLE);
            status.setText("正在重新启动 VS Code…");
            startServer();
            waitForServer();
        }
    }

    /** 本机地址留在 WebView 内，其它统统跳系统浏览器 */
    private boolean routeUrl(String url) {
        if (url == null) return false;
        Uri u;
        try { u = Uri.parse(url); } catch (Throwable t) { return false; }
        String host = u.getHost();
        if (host == null) return false;
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1")) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Boot.log("打开外部链接失败: " + url);
        }
        return true;
    }

    private void waitForServer() {
        final long deadline = System.currentTimeMillis() + 20 * 60 * 1000L;
        new Thread(new Runnable() {
            @Override public void run() {
                while (!isFinishing() && !loaded) {
                    if (Boot.isUp()) {
                        h.post(new Runnable() {
                            @Override public void run() {
                                if (loaded) return;
                                loaded = true;
                                web.loadUrl(Boot.webUrl());
                                web.setVisibility(View.VISIBLE);
                                h.postDelayed(new Runnable() {
                                    @Override public void run() { status.setVisibility(View.GONE); }
                                }, 2000);
                            }
                        });
                        return;
                    }
                    if ("failed".equals(Boot.state())) {
                        h.post(new Runnable() {
                            @Override public void run() { status.setText("启动失败，长按这里查看日志"); }
                        });
                        return;
                    }
                    long left = (deadline - System.currentTimeMillis()) / 1000;
                    final String msg = "正在启动 VS Code…\n状态：" + Boot.state()
                            + "，剩余 " + Math.max(left, 0) + "s\n首次启动需解压约 700MB";
                    h.post(new Runnable() {
                        @Override public void run() { if (!loaded) status.setText(msg); }
                    });
                    if (System.currentTimeMillis() > deadline) {
                        h.post(new Runnable() {
                            @Override public void run() { status.setText("启动超时，长按这里查看日志"); }
                        });
                        return;
                    }
                    try { Thread.sleep(1200); } catch (InterruptedException e) { return; }
                }
            }
        }, "wait-server").start();
    }

    @Override
    public void onBackPressed() {
        if (logView.getVisibility() == View.VISIBLE) {
            logView.setVisibility(View.GONE);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
