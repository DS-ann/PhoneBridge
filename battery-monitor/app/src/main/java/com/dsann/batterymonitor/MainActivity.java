package com.dsann.batterymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String PREFS = "settings";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView voltage;
    private TextView current;
    private Switch notificationSwitch;

    private final Runnable updater = new Runnable() {
        @Override public void run() {
            updateValues();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, dp(36), pad, pad);

        TextView title = text("Battery Monitor", 24);
        root.addView(title, full());

        voltage = text("Voltage: --", 30);
        root.addView(voltage, fullWithTop(32));

        current = text("Current: --", 30);
        root.addView(current, fullWithTop(16));

        notificationSwitch = new Switch(this);
        notificationSwitch.setText("Notification every 5 seconds");
        notificationSwitch.setTextSize(16);
        notificationSwitch.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("notification_enabled", false));
        root.addView(notificationSwitch, fullWithTop(32));

        TextView note = text("The background service runs only while this switch is on.", 13);
        root.addView(note, fullWithTop(16));

        setContentView(root);

        notificationSwitch.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("notification_enabled", enabled).apply();

            if (enabled) {
                if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
                }
                startNotificationService();
            } else {
                stopService(new Intent(this, BatteryNotificationService.class));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        updateValues();
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(updater);
        super.onPause();
    }

    private void updateValues() {
        BatteryReader.Reading r = BatteryReader.read(this);
        voltage.setText("Voltage: " + r.voltageText());
        current.setText("Current: " + r.currentText());
    }

    private void startNotificationService() {
        Intent i = new Intent(this, BatteryNotificationService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private TextView text(String s, int size) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        return v;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams fullWithTop(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(top);
        return p;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
