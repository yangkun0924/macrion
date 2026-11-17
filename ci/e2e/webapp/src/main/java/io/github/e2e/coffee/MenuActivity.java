package io.github.e2e.coffee;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Like the company app: a native header above a web page that scrolls a list inside the page. */
public class MenuActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView header = new TextView(this);
        header.setText("Company header");
        header.setTextSize(22);
        header.setBackgroundColor(Color.rgb(200, 220, 255));
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        header.setPadding(pad, pad, pad, pad);
        layout.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebViewClient(new WebViewClient());
        layout.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(layout);
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
