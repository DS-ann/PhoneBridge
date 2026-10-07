package com.dsann.batterymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        // Resume only if the user had enabled Background recording before reboot.
        // BatteryPulse uses the specialUse FGS type, which is not one of the
        // Android 15 BOOT_COMPLETED-prohibited FGS types.
        boolean enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("notification_enabled", false);
        if (!enabled) return;

        Intent service = new Intent(context, BatteryNotificationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
