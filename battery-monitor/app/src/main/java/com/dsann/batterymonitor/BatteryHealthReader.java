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
    private static final long UNAVAILABLE = Long.MIN_VALUE;
    private static final long DESIGN_CAPACITY_MAH = 4050L;

    private static final String[] NAMES = {
            "charge_full", "charge_full_design", "charge_counter",
            "energy_full", "energy_full_design", "health"
    };

    // Lenovo/MediaTek MTK battery_meter exposes fuel-gauge debug attributes
    // directly under the battery_meter platform device on this phone.
    private static final String[] BATTERY_METER_ROOTS = {
            "/system/devices/platform/battery_meter",
            "/sys/devices/platform/battery_meter"
    };

    private static final String QMAX = "FG_g_fg_dbg_bat_qmax";
    private static final String PERCENT = "FG_g_fg_dbg_percentage";
    private static final String PERCENT_FG = "FG_g_fg_dbg_percentage_fg";
    private static final String PERCENT_VOLT = "FG_g_fg_dbg_percentage_voltmode";
    private static final String CAR = "FG_g_fg_dbg_bat_car";
    private static final String FG_CURRENT = "FG_Current";
    private static final String FG_VOLT = "FG_g_fg_dbg_bat_volt";
    private static final String FG_TEMP = "FG_g_fg_dbg_bat_temp";

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

        Map<String,String> files = readPowerSupply();
        Map<String,String> rootFiles = readAsRoot();
        mergeMissing(files, rootFiles);

        for (Map.Entry<String,String> e : files.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if ("charge_full".equals(key) && d.fullChargeUaH == UNAVAILABLE) d.fullChargeUaH = parse(value);
            else if ("charge_full_design".equals(key) && d.designChargeUaH == UNAVAILABLE) d.designChargeUaH = parse(value);
            else if ("charge_counter".equals(key) && d.remainingChargeUaH == UNAVAILABLE) d.remainingChargeUaH = parse(value);
            else if ("energy_full".equals(key) && d.fullEnergyUWh == UNAVAILABLE) d.fullEnergyUWh = parse(value);
            else if ("energy_full_design".equals(key) && d.designEnergyUWh == UNAVAILABLE) d.designEnergyUWh = parse(value);
            else if ("health".equals(key) && d.rawHealth == null) d.rawHealth = value;
        }

        // On this MTK firmware qmax is the fuel-gauge's aging-adjusted
        // maximum battery capacity, expressed in mAh by the vendor driver.
        String qmax = files.get(QMAX);
        if (d.fullChargeUaH == UNAVAILABLE && qmax != null) {
            long mah = parse(qmax);
            if (mah > 0) {
                d.fullChargeUaH = mah * 1000L;
                d.rawHealth = "MTK qmax / aging capacity: " + mah + " mAh";
                d.sourceFile = files.get("_source_"+QMAX);
            }
        }

        if (d.designChargeUaH == UNAVAILABLE) {
            d.designChargeUaH = DESIGN_CAPACITY_MAH * 1000L;
            d.designSource = "Lenovo rated battery capacity: 4050 mAh";
        }

        if (d.rawHealth == null && files.get(PERCENT) != null) {
            d.rawHealth = "MTK fuel-gauge UI percentage: " + files.get(PERCENT) + "%";
        }

        if (d.sourceFile.equals("Unavailable")) {
            d.sourceFile = sourceFor(files);
        }

        if (d.fullChargeUaH > 0 && d.designChargeUaH > 0) {
            d.ratio = d.fullChargeUaH * 100.0 / d.designChargeUaH;
        }

        d.vendorQmax = qmax;
        d.vendorPercentage = files.get(PERCENT);
        d.vendorPercentageFg = files.get(PERCENT_FG);
        d.vendorPercentageVolt = files.get(PERCENT_VOLT);
        d.vendorCar = files.get(CAR);
        d.vendorCurrent = files.get(FG_CURRENT);
        d.vendorVoltage = files.get(FG_VOLT);
        d.vendorTemperature = files.get(FG_TEMP);
        return d;
    }

    private static void mergeMissing(Map<String,String> target, Map<String,String> fallback) {
        for (Map.Entry<String,String> e : fallback.entrySet()) {
            if (!target.containsKey(e.getKey())) target.put(e.getKey(), e.getValue());
        }
    }

    private static long parse(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return UNAVAILABLE; }
    }

    private static Map<String,String> readPowerSupply() {
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
                    if (v != null && !v.trim().isEmpty()) {
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
            BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
            BufferedReader stdout = new BufferedReader(new InputStreamReader(p.getInputStream()));

            StringBuilder cmd = new StringBuilder();
            cmd.append("for d in /sys/class/power_supply/*; do ");
            cmd.append("[ -d \"$d\" ] || continue; ");
            cmd.append("for n in charge_full charge_full_design charge_counter energy_full energy_full_design health; do ");
            cmd.append("if [ -f \"$d/$n\" ]; then printf '%s|%s|%s\\n' \"$d\" \"$n\" \"$(cat \"$d/$n\")\"; fi; ");
            cmd.append("done; done; ");

            cmd.append("for base in ");
            for (String root : BATTERY_METER_ROOTS) cmd.append(root).append(" ");
            cmd.append("; do [ -d \"$base\" ] || continue; ");
            cmd.append("for n in ").append(QMAX).append(" ").append(PERCENT).append(" ").append(PERCENT_FG)
                    .append(" ").append(PERCENT_VOLT).append(" ").append(CAR).append(" ")
                    .append(FG_CURRENT).append(" ").append(FG_VOLT).append(" ").append(FG_TEMP).append("; do ");
            cmd.append("if [ -f \"$base/$n\" ]; then printf 'BM|%s|%s|%s\\n' \"$base\" \"$n\" \"$(cat \"$base/$n\")\"; fi; ");
            cmd.append("done; done; exit");

            stdin.write(cmd.toString());
            stdin.newLine();
            stdin.flush();

            String line;
            while ((line = stdout.readLine()) != null) {
                String[] parts = line.split("\\|", 4);
                if (parts.length == 3) {
                    out.put(parts[1], parts[2].trim());
                    out.put("_source_"+parts[1], parts[0]+"/"+parts[1]);
                } else if (parts.length == 4 && "BM".equals(parts[0])) {
                    out.put(parts[2], parts[3].trim());
                    out.put("_source_"+parts[2], parts[1]+"/"+parts[2]);
                }
            }
            stdin.close();
            stdout.close();
            p.waitFor();
        } catch (Exception ignored) {
            if (p != null) p.destroy();
        }
        return out;
    }

    private static String sourceFor(Map<String,String> files) {
        for (String name : new String[]{QMAX, "charge_full", "charge_counter", "charge_full_design"}) {
            String s = files.get("_source_"+name);
            if (s != null) return s;
        }
        return files.isEmpty() ? "Unavailable" : "/sys/class/power_supply";
    }

    static final class Data {
        long fullChargeUaH = UNAVAILABLE;
        long remainingChargeUaH = UNAVAILABLE;
        long designChargeUaH = UNAVAILABLE;
        long fullEnergyUWh = UNAVAILABLE;
        long designEnergyUWh = UNAVAILABLE;
        double ratio = Double.NaN;
        String rawHealth;
        String sourceFile = "Unavailable";
        String designSource = "Android/Linux unavailable";
        String vendorQmax, vendorPercentage, vendorPercentageFg, vendorPercentageVolt, vendorCar, vendorCurrent, vendorVoltage, vendorTemperature;

        String charge(long value) {
            return value > 0 ? String.format(Locale.US, "%.0f mAh", value / 1000.0) : "Unavailable";
        }

        String energy(long value) {
            return value > 0 ? String.format(Locale.US, "%.0f mWh", value / 1000.0) : "Unavailable";
        }
    }
}
