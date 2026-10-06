package org.caravel.android;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ViewGroup;

public final class DashboardActivity extends Activity {
    private static final String DASHBOARD_HOST = "localhost";
    private static final int DASHBOARD_PORT = 9119;
    private static final String DASHBOARD =
        "http://localhost:" + DASHBOARD_PORT + "/";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        WebView web = new WebView(this);
        web.setBackgroundColor(
            Color.rgb(4, 28, 28)
        );

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                WebView view,
                WebResourceRequest request
            ) {
                return !isDashboardUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                WebView view,
                String url
            ) {
                return !isDashboardUrl(Uri.parse(url));
            }
        });

        setContentView(
            web,
            new ViewGroup.LayoutParams(-1, -1)
        );

        web.loadUrl(DASHBOARD);
    }

    private static boolean isDashboardUrl(Uri uri) {
        if (uri == null) {
            return false;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();

        boolean localHost =
            "localhost".equalsIgnoreCase(host) ||
            "127.0.0.1".equals(host);

        return "http".equalsIgnoreCase(scheme) &&
            localHost &&
            uri.getPort() == DASHBOARD_PORT;
    }

    @Override
    public void onBackPressed() {
        ViewGroup root =
            (ViewGroup)findViewById(android.R.id.content);

        if (root != null &&
            root.getChildCount() > 0 &&
            root.getChildAt(0) instanceof WebView) {

            WebView web =
                (WebView)root.getChildAt(0);

            if (web.canGoBack()) {
                web.goBack();
                return;
            }
        }

        super.onBackPressed();
    }
}
