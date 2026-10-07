package com.dsann.batterymonitor;

import android.content.Context;
import android.os.BatteryManager;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class BatteryHealthReader {
    private static final String[] NAMES = {
            "charge_full", "charge_full_design", "charge_counter",
            "energy_full", "energy_full_design", "health"
    };

    private BatteryHealthReader() {}

    static Data read(Context context) {
        Data d = new Data();

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            try {
                long v = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (v > 0) d.remainingChargeUaH = v;
            } catch (Throwable ignored) {}
        }

        Map<String,String> files = readSysfs();
        if (files.isEmpty()) files = readAsRoot();

        for (Map.Entry<String,String> e : files.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if ("charge_full".equals(key) && d.fullChargeUaH == Long.MIN_VALUE) d.fullChargeUaH = parse(value);
            else if ("charge_full_design".equals(key) && d.designChargeUaH == Long.MIN_VALUE) d.designChargeUaH = parse(value);
            else if ("charge_counter".equals(key) && d.remainingChargeUaH == Long.MIN_VALUE) d.remainingChargeUaH = parse(value);
            else if ("energy_full".equals(key) && d.fullEnergyUWh == Long.MIN_VALUE) d.fullEnergyUWh = parse(value);
            else if ("energy_full_design".equals(key) && d.designEnergyUWh == Long.MIN_VALUE) d.designEnergyUWh = parse(value);
            else if ("health".equals(key) && d.rawHealth == null) d.rawHealth = value;
        }

        d.sourceFile = sourceFor(files);
        if (d.fullChargeUaH > 0 && d.designChargeUaH > 0) {
            d.ratio = d.fullChargeUaH * 100.0 / d.designChargeUaH;
        }
        return d;
    }

    private static long parse(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return Long.MIN_VALUE; }
    }

    private static Map<String,String> readSysfs() {
        Map<String,String> out = new TreeMap<>();
        File root = new File("/sys/class/power_supply");
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            for (String name : NAMES) {
                File f = new File(dir, name);
                if (!f.canRead()) continue;
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String v = br.readLine();
                    if (v != null && v.trim().length() > 0) {
                        out.put(name, v.trim());
                        if ("battery".equals(dir.getName())) out.put("_source_"+name, f.getAbsolutePath());
                    }
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private static Map<String,String> readAsRoot() {
        Map<String,String> out = new TreeMap<>();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su"});
            BufferedWriter in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
            BufferedReader outReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder cmd = new StringBuilder();
            cmd.append("for d in /sys/class/power_supply/*; do ");
            cmd.append("[ -d \"$d\" ] || continue; ");
            cmd.append("for n in charge_full charge_full_design charge_counter energy_full energy_full_design health; do ");
            cmd.append("if [ -f \"$d/$n\" ]; then printf '%s|%s|%s\\n' \"$d\" \"$n\" \"$(cat \"$d/$n\")\"; fi; ");
            cmd.append("done; done; exit");
            in.write(cmd.toString());
            in.newLine();
            in.flush();

            String line;
            while ((line = outReader.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3 && parts[2].trim().length() > 0) {
                    out.put(parts[1], parts[2].trim());
                    if ("battery".equals(new File(parts[0]).getName())) {
                        out.put("_source_"+parts[1], parts[0]+"/"+parts[1]);
                    }
                }
            }
            in.close();
            outReader.close();
            p.waitFor();
        } catch (Exception ignored) {
            if (p != null) p.destroy();
        }
        return out;
    }

    private static String sourceFor(Map<String,String> files) {
        for (String name : NAMES) {
            String s = files.get("_source_"+name);
            if (s != null) return s;
        }
        return files.isEmpty() ? "Unavailable" : "/sys/class/power_supply";
    }

    static final class Data {
        long fullChargeUaH = Long.MIN_VALUE;
        long remainingChargeUaH = Long.MIN_VALUE;
        long designChargeUaH = Long.MIN_VALUE;
        long fullEnergyUWh = Long.MIN_VALUE;
        long designEnergyUWh = Long.MIN_VALUE;
        double ratio = Double.NaN;
        String rawHealth;
        String sourceFile = "Unavailable";

        String charge(long value) {
            return value > 0 ? String.format(Locale.US, "%.0f mAh", value / 1000.0) : "Unavailable";
        }
        String energy(long value) {
            return value > 0 ? String.format(Locale.US, "%.0f mWh", value / 1000.0) : "Unavailable";
        }
    }
}
