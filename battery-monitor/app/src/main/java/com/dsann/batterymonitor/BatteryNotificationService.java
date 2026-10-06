package com.dsann.batterymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

public class BatteryNotificationService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "battery_monitor";
    private static final String PREFS = "settings";
    private static final String KEY_INTERVAL_MS = "sampling_interval_ms";
    private final Handler handler = new Handler();
    private final Runnable updater = new Runnable() {
        @Override public void run() {
            updateNotification();
            handler.postDelayed(this, getSamplingInterval());
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Reading battery..."));
        updateNotification();
        handler.postDelayed(updater, getSamplingInterval());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private long getSamplingInterval() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_INTERVAL_MS, 15000L);
    }

    private void updateNotification() {
        BatteryReader.Reading r = BatteryReader.read(this);
        SessionStore.sample(this, r);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(r.compact(), mah));
    }

    private Notification buildNotification(String text, double mah) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_info_details)\n                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentTitle("Battery")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS);
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID, "Battery monitor", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Battery voltage and current");\n            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            c.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(updater);
        SessionStore.finish(this);
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}