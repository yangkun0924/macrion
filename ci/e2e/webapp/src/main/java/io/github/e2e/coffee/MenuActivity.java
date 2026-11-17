package io.github.e2e.coffee;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MenuActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        try (java.io.InputStream in = getAssets().open("menu.html")) {
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) read += in.read(bytes, read, bytes.length - read);
            web.loadDataWithBaseURL("https://e2e.invalid/", new String(bytes, "UTF-8"), "text/html", "UTF-8", null);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
