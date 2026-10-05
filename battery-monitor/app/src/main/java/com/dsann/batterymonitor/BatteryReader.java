package com.dsann.batterymonitor;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

final class BatteryReader {
    private static final String FG_CURRENT =
            "/sys/devices/platform/battery_meter/FG_Current";
    private static final String INSTAT_VOLT =
            "/sys/class/power_supply/battery/InstatVolt";

    private BatteryReader() {}

    static Reading read(Context context) {
        Long currentMa = readSysfs(FG_CURRENT);
        Long voltageMv = readSysfs(INSTAT_VOLT);

        // Fallbacks for devices without the MTK vendor nodes.
        if (currentMa == null) {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null && Build.VERSION.SDK_INT >= 21) {
                long currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (currentUa != 0) currentMa = Math.round(currentUa / 1000.0);
            }
        }

        if (currentMa == null) {
            Long v = readSysfs("/sys/class/power_supply/battery/current_now");
            if (v != null) currentMa = Math.round(v / 1000.0);
        }

        if (voltageMv == null) {
            android.content.Intent battery =
                    context.registerReceiver(null, new android.content.IntentFilter(
                            android.content.Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int mv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                if (mv > 0) voltageMv = (long) mv;
            }
        }

        if (voltageMv == null) {
            Long v = readSysfs("/sys/class/power_supply/battery/voltage_now");
            if (v != null) voltageMv = Math.round(v / 1000.0);
        }

        return new Reading(
                voltageMv != null ? voltageMv * 1000L : Long.MIN_VALUE,
                currentMa != null ? currentMa * 1000L : Long.MIN_VALUE
        );
    }

    private static Long readSysfs(String path) {
        File f = new File(path);
        if (!f.canRead()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String s = br.readLine();
            if (s == null) return null;
            return Long.parseLong(s.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    static final class Reading {
        final long voltageUv;
        final long currentUa;

        Reading(long voltageUv, long currentUa) {
            this.voltageUv = voltageUv;
            this.currentUa = currentUa;
        }

        String voltageText() {
            return voltageUv > 0
                    ? String.format(java.util.Locale.US, "%.3f V", voltageUv / 1_000_000.0)
                    : "N/A";
        }

        String currentText() {
            if (currentUa == Long.MIN_VALUE) return "N/A";
            return String.format(java.util.Locale.US, "%+d mA",
                    Math.round(currentUa / 1000.0));
        }

        String compact() {
            return voltageText() + "  •  " + currentText();
        }
    }
}
