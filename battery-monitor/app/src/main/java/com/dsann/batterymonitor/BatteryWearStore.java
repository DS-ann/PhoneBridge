package com.dsann.batterymonitor;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

final class BatteryWearStore {
    private static final String PREFS = "battery_wear";
    private static final String KEY_WEAR = "wear_percent";
    private static final String KEY_HEALTH = "health_percent";
    private static final String KEY_FULL = "full_uah";
    private static final String KEY_DESIGN = "design_uah";
    private static final String KEY_UPDATED = "updated";
    private static final String[] FULL_PATHS = {
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full"
    };
    private static final String[] DESIGN_PATHS = {
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design"
    };

    private BatteryWearStore() {}

    static synchronized void update(Context c) {
        if (!isCharging(c)) return;

        long full = firstPositive(FULL_PATHS);
        long design = firstPositive(DESIGN_PATHS);
        if (full <= 0 || design <= 0) return;

        full = normalizeCapacity(full);
        design = normalizeCapacity(design);
        if (full <= 0 || design <= 0) return;

        double health = Math.min(100.0, (full * 100.0) / design);
        double wear = Math.max(0.0, Math.min(100.0, 100.0 - health));

        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_WEAR, (float) wear)
                .putFloat(KEY_HEALTH, (float) health)
                .putLong(KEY_FULL, full)
                .putLong(KEY_DESIGN, design)
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply();
    }

    static Snapshot get(Context c) {
        android.content.SharedPreferences p =
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float wear = p.getFloat(KEY_WEAR, -1f);
        float health = p.getFloat(KEY_HEALTH, -1f);
        if (wear < 0 || health < 0) return new Snapshot(-1, -1, 0, 0);

        return new Snapshot(
                wear,
                health,
                p.getLong(KEY_FULL, 0),
                p.getLong(KEY_DESIGN, 0)
        );
    }

    static boolean isCharging(Context c) {
        Intent i = c.registerReceiver(null,
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return i != null && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private static long firstPositive(String[] paths) {
        for (String path : paths) {
            Long value = readLong(path);
            if (value != null && value > 0) return value;
        }
        return 0;
    }

    private static Long readLong(String path) {
        File f = new File(path);
        if (!f.canRead()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String s = br.readLine();
            return s == null ? null : Long.parseLong(s.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long normalizeCapacity(long value) {
        // Standard Linux power_supply capacity is µAh. Some vendor trees expose mAh.
        if (value > 0 && value < 100000) return value * 1000L;
        return value;
    }

    static final class Snapshot {
        final double wear;
        final double health;
        final long fullUah;
        final long designUah;

        Snapshot(double wear, double health, long fullUah, long designUah) {
            this.wear = wear;
            this.health = health;
            this.fullUah = fullUah;
            this.designUah = designUah;
        }

        boolean available() { return wear >= 0 && health >= 0; }

        double designCapacityMah() { return designUah > 0 ? designUah / 1000.0 : 0; }

        String capacityText() {
            if (!available() || fullUah <= 0 || designUah <= 0) return "";
            return String.format(java.util.Locale.US, "%.0f / %.0f mAh",
                    fullUah / 1000.0, designUah / 1000.0);
        }
    }
}
