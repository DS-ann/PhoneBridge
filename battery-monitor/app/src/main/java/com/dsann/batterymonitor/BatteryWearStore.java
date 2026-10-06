package com.dsann.batterymonitor;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;

final class BatteryWearStore {
    private static final String PREFS="battery_wear";
    private static final String KEY_WEAR="wear_percent",KEY_HEALTH="health_percent",KEY_FULL="full_uah",KEY_DESIGN="design_uah",KEY_UPDATED="updated";
    private static final String KEY_EST_FULL="estimated_full_uah",KEY_EST_HEALTH="estimated_health_percent",KEY_EST_WEAR="estimated_wear_percent",KEY_QUALIFIED="qualified_sessions";
    private static final String KEY_FULL_SOURCE="full_source",KEY_DESIGN_SOURCE="design_source";
    private static final String[] FULL_PATHS={"/sys/class/power_supply/battery/charge_full","/sys/class/power_supply/bms/charge_full"};
    private static final String[] DESIGN_PATHS={"/sys/class/power_supply/battery/charge_full_design","/sys/class/power_supply/bms/charge_full_design"};
    private static final int HEALTH_MIN_CHARGE_PERCENT=40;
    private static final double ACCUBATTERY_VMAX=4.35,ACCUBATTERY_LINEAR_CUTOFF=3.95;
    private static final double MIN_ESTIMATED_CAPACITY_MAH=1000,MAX_ESTIMATED_CAPACITY_MAH=30000;
    private BatteryWearStore(){}

    static synchronized void update(Context c){
        long full=normalizeCapacity(firstPositive(FULL_PATHS)),design=normalizeCapacity(firstPositive(DESIGN_PATHS));
        android.content.SharedPreferences.Editor e=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit();
        if(full>0)e.putLong(KEY_FULL,full).putString(KEY_FULL_SOURCE,fullSource());
        if(design>0)e.putLong(KEY_DESIGN,design).putString(KEY_DESIGN_SOURCE,designSource());
        if(full>0&&design>0){
            double health=clamp(full*100.0/design,0,110),wear=Math.max(0,100-health);
            e.putFloat(KEY_WEAR,(float)wear).putFloat(KEY_HEALTH,(float)health);
        }
        if(full>0||design>0)e.putLong(KEY_UPDATED,System.currentTimeMillis());
        e.apply();
    }

    static synchronized void updateEstimatedHealth(Context c){
        long design=normalizeCapacity(firstPositive(DESIGN_PATHS));
        ArrayList<SessionStore.Record> q=new ArrayList<>();
        for(SessionStore.Record r:SessionStore.getRecords(c)){
            if(!r.charging)continue;
            int delta=r.endPercent-r.startPercent;
            if(delta<HEALTH_MIN_CHARGE_PERCENT||r.mah<0.1)continue;
            double cap=r.mah*100.0/delta;
            if(cap>=MIN_ESTIMATED_CAPACITY_MAH&&cap<=MAX_ESTIMATED_CAPACITY_MAH){
                if(design<=0|| (cap>=design/1000.0*0.55&&cap<=design/1000.0*1.25))q.add(r);
            }
        }
        if(q.isEmpty())return;
        Collections.sort(q,(a,b)->Long.compare(b.start,a.start));
        int n=Math.min(8,q.size()); ArrayList<Double> caps=new ArrayList<>();
        double weighted=0,ws=0;
        for(int i=0;i<n;i++){
            SessionStore.Record r=q.get(i);
            double cap=r.mah*100.0/(r.endPercent-r.startPercent);
            double weight=Math.max(1,Math.min(2.5,(r.endPercent-r.startPercent)/40.0));
            caps.add(cap); weighted+=cap*weight; ws+=weight;
        }
        Collections.sort(caps);
        double median=caps.get(caps.size()/2);
        if(caps.size()%2==0)median=(caps.get(caps.size()/2-1)+caps.get(caps.size()/2))/2.0;
        double estimated=median*.7+(weighted/ws)*.3;
        android.content.SharedPreferences.Editor e=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putFloat(KEY_EST_WEAR,-1f).putFloat(KEY_EST_HEALTH,-1f)
                .putLong(KEY_EST_FULL,Math.round(estimated*1000)).putInt(KEY_QUALIFIED,n)
                .putLong(KEY_UPDATED,System.currentTimeMillis());
        if(design>0){
            double health=clamp(estimated*100.0/(design/1000.0),0,110),wear=Math.max(0,100-health);
            e.putFloat(KEY_EST_HEALTH,(float)health).putFloat(KEY_EST_WEAR,(float)wear);
        }
        e.apply();
    }

    static int healthQualificationPercent(){return HEALTH_MIN_CHARGE_PERCENT;}
    static double estimateEndVoltage(int percent){
        double[] soc={0,5,10,20,30,40,50,60,70,80,85,90,95,100},v={3.52,3.60,3.65,3.69,3.72,3.75,3.77,3.79,3.82,3.87,3.92,4.00,4.10,4.20};
        double p=Math.max(0,Math.min(100,percent));
        for(int i=1;i<soc.length;i++)if(p<=soc[i]){
            double t=(p-soc[i-1])/(soc[i]-soc[i-1]);
            double ideal=v[i-1]+t*(v[i]-v[i-1]);
            return 3.52+(ideal-3.52)*(ACCUBATTERY_VMAX-3.52)/(4.20-3.52);
        }
        return ACCUBATTERY_VMAX;
    }
    private static double wearAtVoltage(double v){
        if(v>=ACCUBATTERY_LINEAR_CUTOFF)return Math.pow(2,10*(v-ACCUBATTERY_VMAX));
        double cutoff=Math.pow(2,10*(ACCUBATTERY_LINEAR_CUTOFF-ACCUBATTERY_VMAX));
        return cutoff*Math.max(0,Math.min(1,(v-3.52)/(ACCUBATTERY_LINEAR_CUTOFF-3.52)));
    }
    static double wearCycles(int s,int e){if(s<0||e<0||e<=s)return 0;return Math.max(0,wearAtVoltage(estimateEndVoltage(e))-wearAtVoltage(estimateEndVoltage(s)));}
    static double chargeEfficiency(int s,int e){double cycles=wearCycles(s,e);return cycles>0?(e-s)/100.0/cycles*100:0;}

    static Snapshot get(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        return new Snapshot(p.getFloat(KEY_WEAR,-1),p.getFloat(KEY_HEALTH,-1),p.getLong(KEY_FULL,0),p.getLong(KEY_DESIGN,0),
                p.getFloat(KEY_EST_WEAR,-1),p.getFloat(KEY_EST_HEALTH,-1),p.getLong(KEY_EST_FULL,0),p.getInt(KEY_QUALIFIED,0),p.getLong(KEY_UPDATED,0),
                p.getString(KEY_FULL_SOURCE,"Unavailable"),p.getString(KEY_DESIGN_SOURCE,"Unavailable"));
    }
    static boolean isCharging(Context c){Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));return i!=null&&i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;}
    static long getFullUah(){return normalizeCapacity(firstPositive(FULL_PATHS));}
    static long getDesignUah(){return normalizeCapacity(firstPositive(DESIGN_PATHS));}
    static String fullSource(){for(String p:FULL_PATHS)if(readRaw(p)>0)return p;return "Unavailable";}
    static String designSource(){for(String p:DESIGN_PATHS)if(readRaw(p)>0)return p;return "Unavailable";}
    private static long firstPositive(String[] paths){for(String p:paths){long v=readRaw(p);if(v>0)return v;}return 0;}
    private static long readRaw(String path){File f=new File(path);if(!f.canRead())return 0;try(BufferedReader br=new BufferedReader(new FileReader(f))){String s=br.readLine();return s==null?0:Long.parseLong(s.trim());}catch(Exception e){return 0;}}
    private static long normalizeCapacity(long v){return v>0&&v<100000?v*1000:v;}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}

    static final class Snapshot{
        final double wear,health,estimatedWear,estimatedHealth;final long fullUah,designUah,estimatedFullUah,updated;final int qualifiedSessions;
        final String fullSource,designSource;
        Snapshot(double w,double h,long f,long d,double ew,double eh,long ef,int q,long u,String fs,String ds){wear=w;health=h;fullUah=f;designUah=d;estimatedWear=ew;estimatedHealth=eh;estimatedFullUah=ef;qualifiedSessions=q;updated=u;fullSource=fs;designSource=ds;}
        boolean measuredCapacityAvailable(){return fullUah>0&&designUah>0;}
        boolean estimatedCapacityAvailable(){return estimatedFullUah>0;}
        boolean available(){return measuredCapacityAvailable()||estimatedCapacityAvailable();}
        double designCapacityMah(){return designUah>0?designUah/1000.0:0;}
        double fullCapacityMah(){return fullUah>0?fullUah/1000.0:0;}
        double estimatedCapacityMah(){return estimatedFullUah>0?estimatedFullUah/1000.0:0;}
        String capacityText(){if(measuredCapacityAvailable())return String.format(java.util.Locale.US,"%.0f / %.0f mAh",fullCapacityMah(),designCapacityMah());if(estimatedCapacityAvailable())return String.format(java.util.Locale.US,"~%.0f mAh estimated",estimatedCapacityMah());return "Unavailable";}
    }
}
