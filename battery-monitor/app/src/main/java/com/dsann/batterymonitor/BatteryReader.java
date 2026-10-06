package com.dsann.batterymonitor;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

final class BatteryReader {
    private static final String FG_CURRENT =
            "/sys/devices/platform/battery_meter/FG_Current";
    private static final String INSTAT_VOLT =
            "/sys/class/power_supply/battery/InstatVolt";

    private static RootReader rootReader;

    private BatteryReader() {}

    static Reading read(Context context) {
        Long currentRaw = readSysfs(FG_CURRENT);
        Long voltageMv = readSysfs(INSTAT_VOLT);

        // On this MTK phone FG_Current is reported in 0.1 mA units.
        // Example: 2437 means 243.7 mA, not 2437 mA.
        if (currentRaw != null) {
            long currentUa = currentRaw * 100L;
            return new Reading(
                    voltageMv != null ? voltageMv * 1000L : Long.MIN_VALUE,
                    currentUa
            );
        }

            // Generic Android/sysfs fallback: these current values are in microamps.
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null && Build.VERSION.SDK_INT >= 21) {
            long currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (currentUa != 0) {
                return new Reading(
                        voltageMv != null ? voltageMv * 1000L : Long.MIN_VALUE,
                        currentUa
                );
            }
        }

        Long fallbackUa = readSysfs("/sys/class/power_supply/battery/current_now");
        if (fallbackUa != null) {
            return new Reading(
                    voltageMv != null ? voltageMv * 1000L : Long.MIN_VALUE,
                    fallbackUa
            );
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
                Long.MIN_VALUE
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

    static final class RootReader {
        private final Process process;
        private final BufferedWriter stdin;
        private final BufferedReader stdout;

        RootReader() throws IOException {
            process = Runtime.getRuntime().exec(new String[]{"su"});
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
        }

        boolean isAlive() {
            try {
                process.exitValue();
                return false;
            } catch (IllegalThreadStateException e) {
                return true;
            }
        }

        RootReading read() throws IOException {
            stdin.write("cat " + FG_CURRENT);
            stdin.newLine();
            stdin.write("cat " + INSTAT_VOLT);
            stdin.newLine();
            stdin.flush();

            String current = stdout.readLine();
            String voltage = stdout.readLine();
            if (current == null || voltage == null) throw new IOException("su closed");

            return new RootReading(
                    Long.parseLong(current.trim()),
                    Long.parseLong(voltage.trim())
            );
        }

        void close() {
            try { stdin.write("exit"); stdin.newLine(); stdin.flush(); } catch (Exception ignored) {}
            try { stdin.close(); } catch (Exception ignored) {}
            try { stdout.close(); } catch (Exception ignored) {}
            process.destroy();
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
            return String.format(java.util.Locale.US, "%+.1f mA",
                    currentUa / 1000.0);
        }

        String compact() {
            return voltageText() + "  •  " + currentText();
        }
    }
}
