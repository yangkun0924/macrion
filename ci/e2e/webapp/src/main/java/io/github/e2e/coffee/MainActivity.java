package io.github.e2e.coffee;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText("E2E Coffee home");
        title.setTextSize(24);
        layout.addView(title);
        Button order = new Button(this);
        order.setText("Order menu");
        order.setOnClickListener(v -> startActivity(new Intent(this, MenuActivity.class)));
        layout.addView(order);
        setContentView(layout);
    }
}
