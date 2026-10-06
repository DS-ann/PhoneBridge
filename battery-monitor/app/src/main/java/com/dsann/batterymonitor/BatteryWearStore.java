package com.dsann.batterymonitor;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

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
    private static final int HEALTH_MIN_CHARGE_PERCENT = 40;
    private static final double ACCUBATTERY_VMAX = 4.35;
    private static final double ACCUBATTERY_LINEAR_CUTOFF = 3.95;
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

    static synchronized void updateEstimatedHealth(Context c) {
        long designUah = firstPositive(DESIGN_PATHS);
        if (designUah <= 0) return;
        designUah = normalizeCapacity(designUah);

        ArrayList<SessionStore.Record> records = SessionStore.getRecords(c);
        double sum = 0;
        int count = 0;
        for (SessionStore.Record rec : records) {
            if (!rec.charging) continue;
            int delta = rec.endPercent - rec.startPercent;
            if (delta < HEALTH_MIN_CHARGE_PERCENT || rec.mah < 0.1) continue;
            double estimated = rec.mah * 100.0 / delta;
            if (estimated > 0 && estimated < designUah / 1000.0 * 1.5) {
                sum += estimated;
                if (++count >= 5) break;
            }
        }
        if (count == 0) return;

        double estimatedCapacity = sum / count;
        double health = Math.min(110.0, (estimatedCapacity * 100.0) / (designUah / 1000.0));
        double wear = Math.max(0.0, 100.0 - health);

        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_WEAR, (float) wear)
                .putFloat(KEY_HEALTH, (float) health)
                .putLong(KEY_FULL, Math.round(estimatedCapacity * 1000.0))
                .putLong(KEY_DESIGN, designUah)
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply();
    }

    static int healthQualificationPercent() { return HEALTH_MIN_CHARGE_PERCENT; }

    /**
     * AccuBattery documents wear as an exponential function of end voltage:
     * every 0.10 V lower end voltage approximately halves wear. Its private
     * percentage-to-voltage ML model is not public, so we use the documented
     * idealized Li-ion discharge curve shape and normalize its 3.52-4.20 V
     * range to the documented 4.35 V Vmax.
     */
    static double estimateEndVoltage(int percent) {
        double[] soc={0,5,10,20,30,40,50,60,70,80,85,90,95,100};
        double[] v={3.52,3.60,3.65,3.69,3.72,3.75,3.77,3.79,3.82,3.87,3.92,4.00,4.10,4.20};
        double p=Math.max(0,Math.min(100,percent));
        for(int i=1;i<soc.length;i++){
            if(p<=soc[i]){
                double t=(p-soc[i-1])/(soc[i]-soc[i-1]);
                double ideal=v[i-1]+t*(v[i]-v[i-1]);
                return 3.52+(ideal-3.52)*(ACCUBATTERY_VMAX-3.52)/(4.20-3.52);
            }
        }
        return ACCUBATTERY_VMAX;
    }

    static double wearCycles(int startPercent,int endPercent) {
        if(startPercent<0||endPercent<0||endPercent<=startPercent)return 0;
        double startV=estimateEndVoltage(startPercent);
        double endV=estimateEndVoltage(endPercent);
        double vmax=ACCUBATTERY_VMAX;
        double wearEnd;
        if(endV>=ACCUBATTERY_LINEAR_CUTOFF){
            wearEnd=Math.pow(2.0,10.0*(endV-vmax));
        }else{
            double cutoffWear=Math.pow(2.0,10.0*(ACCUBATTERY_LINEAR_CUTOFF-vmax));
            double lowRange=(endV-3.52)/(ACCUBATTERY_LINEAR_CUTOFF-3.52);
            wearEnd=cutoffWear*Math.max(0,Math.min(1,lowRange));
        }
        double wearStart;
        if(startV>=ACCUBATTERY_LINEAR_CUTOFF){
            wearStart=Math.pow(2.0,10.0*(startV-vmax));
        }else{
            double cutoffWear=Math.pow(2.0,10.0*(ACCUBATTERY_LINEAR_CUTOFF-vmax));
            double lowRange=(startV-3.52)/(ACCUBATTERY_LINEAR_CUTOFF-3.52);
            wearStart=cutoffWear*Math.max(0,Math.min(1,lowRange));
        }
        return Math.max(0,wearEnd-wearStart);
    }

    static double chargeEfficiency(int startPercent,int endPercent) {
        if(startPercent<0||endPercent<=startPercent)return 0;
        double charged=(endPercent-startPercent)/100.0;
        double cycles=wearCycles(startPercent,endPercent);
        return cycles>0?charged/cycles*100.0:0;
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
