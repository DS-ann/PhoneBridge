package com.dsann.batterymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // Android 12+ does not normally allow an app to promote a foreground
        // service from BOOT_COMPLETED while the app is in the background.
        // Do not start the service here; MainActivity starts it when the user
        // next opens the app with recording enabled.
    }
}
