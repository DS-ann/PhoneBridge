package com.dsann.batterymonitor;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BatteryWearStore {
    private static final String PREFS="battery_wear";
    private static final String KEY_WEAR="wear_percent",KEY_HEALTH="health_percent",KEY_FULL="full_uah",KEY_DESIGN="design_uah",KEY_UPDATED="updated";
    private static final String KEY_EST_FULL="estimated_full_uah",KEY_EST_HEALTH="estimated_health_percent",KEY_EST_WEAR="estimated_wear_percent",KEY_QUALIFIED="qualified_sessions";
    private static final String KEY_FULL_SOURCE="full_source",KEY_DESIGN_SOURCE="design_source",KEY_CHARGE_COUNTER="charge_counter_uah";
    private static final String KEY_FULL_ENERGY="full_energy_uwh",KEY_DESIGN_ENERGY="design_energy_uwh",KEY_FULL_ENERGY_SOURCE="full_energy_source",KEY_DESIGN_ENERGY_SOURCE="design_energy_source";
    private static final String POWER_SUPPLY_ROOT="/sys/class/power_supply";
    private static final int HEALTH_MIN_CHARGE_PERCENT=40;
    private static final double ACCUBATTERY_VMAX=4.35,ACCUBATTERY_LINEAR_CUTOFF=3.95;
    private static final double MIN_ESTIMATED_CAPACITY_MAH=1000,MAX_ESTIMATED_CAPACITY_MAH=30000;
    private BatteryWearStore(){}

    static synchronized void update(Context c){
        SourceValue fullSourceValue=discoverCapacity(c,false);
        SourceValue designSourceValue=discoverCapacity(c,true);
        long full=fullSourceValue.value,design=designSourceValue.value;
        long fullEnergy=firstReadable("energy_full"),designEnergy=firstReadable("energy_full_design");
        long chargeCounter=readBatteryManagerChargeCounter(c);
        android.content.SharedPreferences.Editor e=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit();
        if(full>0)e.putLong(KEY_FULL,full).putString(KEY_FULL_SOURCE,fullSourceValue.source);
        if(design>0)e.putLong(KEY_DESIGN,design).putString(KEY_DESIGN_SOURCE,designSourceValue.source);
        if(fullEnergy>0)e.putLong(KEY_FULL_ENERGY,fullEnergy).putString(KEY_FULL_ENERGY_SOURCE,fullEnergySource());
        if(designEnergy>0)e.putLong(KEY_DESIGN_ENERGY,designEnergy).putString(KEY_DESIGN_ENERGY_SOURCE,designEnergySource());
        if(chargeCounter>0)e.putLong(KEY_CHARGE_COUNTER,chargeCounter);
        if(full>0&&design>0){
            double health=clamp(full*100.0/design,0,110),wear=Math.max(0,100-health);
            e.putFloat(KEY_WEAR,(float)wear).putFloat(KEY_HEALTH,(float)health);
        }else if(fullEnergy>0&&designEnergy>0){
            double health=clamp(fullEnergy*100.0/designEnergy,0,110),wear=Math.max(0,100-health);
            e.putFloat(KEY_WEAR,(float)wear).putFloat(KEY_HEALTH,(float)health);
        }
        if(full>0||design>0||fullEnergy>0||designEnergy>0)e.putLong(KEY_UPDATED,System.currentTimeMillis());
        e.apply();
    }

    static synchronized void updateEstimatedHealth(Context c){
        long design=discoverCapacity(c,true).value;
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
                p.getLong(KEY_FULL_ENERGY,0),p.getLong(KEY_DESIGN_ENERGY,0),
                p.getFloat(KEY_EST_WEAR,-1),p.getFloat(KEY_EST_HEALTH,-1),p.getLong(KEY_EST_FULL,0),p.getInt(KEY_QUALIFIED,0),p.getLong(KEY_UPDATED,0),
                p.getString(KEY_FULL_SOURCE,"Unavailable"),p.getString(KEY_DESIGN_SOURCE,"Unavailable"),
                p.getString(KEY_FULL_ENERGY_SOURCE,"Unavailable"),p.getString(KEY_DESIGN_ENERGY_SOURCE,"Unavailable"));
    }
    static boolean isCharging(Context c){Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));return i!=null&&i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;}
    static long getFullUah(){return discoverCapacity(null,false).value;}
    static long getDesignUah(){return discoverCapacity(null,true).value;}
    static long getChargeCounterUah(Context c){
        long v=readBatteryManagerChargeCounter(c);
        return v>0?v:c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong(KEY_CHARGE_COUNTER,0);
    }
    private static long readBatteryManagerChargeCounter(Context c){
        try{
            BatteryManager bm=(BatteryManager)c.getSystemService(Context.BATTERY_SERVICE);
            if(bm==null)return 0;
            long v=bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            return v>0?v:0;
        }catch(Exception ignored){return 0;}
    }
    static String androidHealth(Context c){try{Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(i==null)return "Unavailable";int h=i.getIntExtra(BatteryManager.EXTRA_HEALTH,-1);switch(h){case BatteryManager.BATTERY_HEALTH_GOOD:return "GOOD";case BatteryManager.BATTERY_HEALTH_OVERHEAT:return "OVERHEAT";case BatteryManager.BATTERY_HEALTH_DEAD:return "DEAD";case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:return "OVER_VOLTAGE";case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:return "FAILURE";case BatteryManager.BATTERY_HEALTH_COLD:return "COLD";default:return "Unavailable";}}catch(Exception ignored){return "Unavailable";}}
    private static int batteryLevel(Context c){
        try{
            Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(i==null)return -1;
            int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            return level>=0&&scale>0?Math.round(level*100f/scale):-1;
        }catch(Exception ignored){return -1;}
    }
    static String fullSource(){return discoverCapacity(null,false).source;}
    static String designSource(){return discoverCapacity(null,true).source;}
    static String fullEnergySource(){return firstReadableSource("energy_full");}
    static String designEnergySource(){return firstReadableSource("energy_full_design");}

    private static SourceValue discoverCapacity(Context c,boolean design){
        SourceValue v=discoverFromSysfs(design);
        if(v.value>0)return v;
        if(c!=null){
            v=discoverFromBatteryIntent(c,design);if(v.value>0)return v;
            if(design){v=discoverFromFrameworkResources(c);if(v.value>0)return v;}
        }
        v=discoverFromProperties(design);if(v.value>0)return v;
        if(design){
            v=discoverFromPowerProfile();if(v.value>0)return v;
            v=discoverFromKnownDeviceProfile();if(v.value>0)return v;
        }
        return new SourceValue(0,"Unavailable");
    }

    private static SourceValue discoverFromSysfs(boolean design){
        String[] roots={"/sys/class/power_supply","/sys/devices/virtual/power_supply","/sys/devices/platform"};
        String[] preferred=design?new String[]{"charge_full_design","battery_design_capacity","design_capacity","capacity_design","fg_design_capacity","rated_capacity","nominal_capacity","battery_capacity_design","battery_capacity","capacity_full_design"}:new String[]{"charge_full","full_charge_capacity","full_capacity","battery_full_capacity","fg_full_capacity","fcc","qmax"};
        for(String root:roots){SourceValue v=scanTree(new File(root),preferred,0,new int[]{0});if(v.value>0)return v;}
        return new SourceValue(0,"Unavailable");
    }
    private static SourceValue scanTree(File dir,String[] names,int depth,int[] count){
        if(dir==null||depth>5||count[0]>6000||!dir.isDirectory()||!dir.canRead())return new SourceValue(0,"Unavailable");
        File[] files=dir.listFiles();if(files==null)return new SourceValue(0,"Unavailable");
        for(File f:files){count[0]++;if(f.isFile()&&f.canRead()){String n=f.getName().toLowerCase(Locale.US);for(String wanted:names){if(n.equals(wanted)||n.contains(wanted)){long raw=readNumeric(f);long uah=normalizeCapacity(raw);if(isPlausibleCapacity(uah))return new SourceValue(uah,f.getAbsolutePath());}}}}
        for(File f:files)if(f.isDirectory()){SourceValue v=scanTree(f,names,depth+1,count);if(v.value>0)return v;}
        if(designCandidate(dir.getName())){
            for(File f:files)if(f.isFile()&&f.canRead()){
                String n=f.getName().toLowerCase(Locale.US);
                if((n.contains("capacity")||n.contains("charge")||n.contains("qmax"))&&
                        (n.contains("design")||n.contains("rated")||n.contains("nominal")||n.contains("full"))){
                    long uah=normalizeCapacity(readNumeric(f));
                    if(isPlausibleCapacity(uah))return new SourceValue(uah,f.getAbsolutePath());
                }
            }
        }
        return new SourceValue(0,"Unavailable");
    }
    private static boolean designCandidate(String n){
        String x=n.toLowerCase(Locale.US);
        return x.contains("battery")||x.contains("bms")||x.contains("fuel")||x.contains("power")||x.contains("charger")||x.contains("gauge");
    }
    private static SourceValue discoverFromBatteryIntent(Context c,boolean design){
        try{Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(i==null)return new SourceValue(0,"Unavailable");Bundle b=i.getExtras();if(b==null)return new SourceValue(0,"Unavailable");
            for(String key:b.keySet()){String k=key.toLowerCase(Locale.US);if(!isCapacityKey(k,design))continue;Object o=b.get(key);long raw=numberFromObject(o);long uah=normalizeCapacity(raw);if(isPlausibleCapacity(uah))return new SourceValue(uah,"Android ACTION_BATTERY_CHANGED extra: "+key);}
        }catch(Throwable ignored){} return new SourceValue(0,"Unavailable");
    }
    private static boolean isCapacityKey(String k,boolean design){
        if(k.equals("capacity")||k.equals("level")||k.contains("percent")||k.contains("temperature")||k.contains("voltage")||k.contains("current"))return false;
        if(design)return (k.contains("design")&&(k.contains("cap")||k.contains("charge")||k.contains("energy")))||k.contains("rated_capacity")||k.contains("nominal_capacity")||k.contains("battery_capacity_design")||k.equals("battery_capacity")||k.equals("battery.capacity")||k.equals("totalbatterycapacity")||k.equals("total_battery_capacity");
        return k.contains("charge_full")||k.contains("full_charge")||k.contains("fullcapacity")||k.contains("full_capacity")||k.endsWith("fcc")||k.equals("fcc")||k.contains("qmax");
    }
    private static SourceValue discoverFromFrameworkResources(Context c){
        try{
            String[] names={"config_batteryCapacity","config_battery_capacity","battery_capacity","batteryCapacity"};
            for(String name:names){
                int id=c.getResources().getIdentifier(name,"integer","android");
                if(id!=0){
                    int raw=c.getResources().getInteger(id);
                    long uah=normalizeCapacity(raw);
                    if(isPlausibleCapacity(uah))return new SourceValue(uah,"Android framework resource: "+name);
                }
            }
        }catch(Throwable ignored){}
        return new SourceValue(0,"Unavailable");
    }

    private static SourceValue discoverFromPowerProfile(){
        String[] paths={
            "/system/etc/power_profile.xml",
            "/vendor/etc/power_profile.xml",
            "/product/etc/power_profile.xml",
            "/odm/etc/power_profile.xml",
            "/system_ext/etc/power_profile.xml"
        };
        Pattern p=Pattern.compile("<item\\s+name=[\\\"']battery\\.capacity[\\\"']\\s*>([0-9]+(?:\\.[0-9]+)?)\\s*</item>",Pattern.CASE_INSENSITIVE);
        for(String path:paths){
            File f=new File(path);
            if(!f.isFile()||!f.canRead())continue;
            try(BufferedReader br=new BufferedReader(new FileReader(f))){
                String line; StringBuilder all=new StringBuilder();
                while((line=br.readLine())!=null)all.append(line);
                Matcher m=p.matcher(all.toString());
                if(m.find()){
                    long uah=normalizeCapacity(numberFromObject(m.group(1)));
                    if(isPlausibleCapacity(uah))return new SourceValue(uah,"power_profile.xml: "+path);
                }
            }catch(Exception ignored){}
        }
        return new SourceValue(0,"Unavailable");
    }

    private static SourceValue discoverFromKnownDeviceProfile(){
        try{
            String model=android.os.Build.MODEL==null?"":android.os.Build.MODEL.toLowerCase(Locale.US);
            String device=android.os.Build.DEVICE==null?"":android.os.Build.DEVICE.toLowerCase(Locale.US);
            if(model.contains("redmi pad 2")||model.contains("25040rp0")||device.contains("25040rp0")){
                // Xiaomi specifies a 9000 mAh typical battery for the Redmi Pad 2. Use the
                // device's published 9000 mAh capacity as the design-capacity fallback when HyperOS
                // does not expose the fuel-gauge design value to this ordinary app.
                return new SourceValue(9000000L,"device profile: Redmi Pad 2 published capacity");
            }
        }catch(Throwable ignored){}
        return new SourceValue(0,"Unavailable");
    }

    private static SourceValue discoverFromProperties(boolean design){
        try{Process p=Runtime.getRuntime().exec(new String[]{"/system/bin/getprop"});BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream()));String line;Pattern pat=Pattern.compile("\\[([^]]+)\\]\\s*:\\s*\\[([^]]*)\\]");
            while((line=br.readLine())!=null){Matcher m=pat.matcher(line);if(!m.find())continue;String key=m.group(1).toLowerCase(Locale.US),val=m.group(2);if(!key.contains("batt")&&!key.contains("power")&&!key.contains("fuel")&&!key.contains("capacity")&&!key.contains("qmax")&&!key.contains("fcc"))continue;if(!isCapacityKey(key,design))continue;long uah=normalizeCapacity(numberFromObject(val));if(isPlausibleCapacity(uah))return new SourceValue(uah,"system property: "+m.group(1));}
        }catch(Throwable ignored){} return new SourceValue(0,"Unavailable");
    }
    private static long numberFromObject(Object o){
        if(o==null)return 0;
        try{
            if(o instanceof Number)return ((Number)o).longValue();
            String s=String.valueOf(o).trim();
            if(s.isEmpty())return 0;
            return Math.round(Double.parseDouble(s));
        }catch(Exception e){return 0;}
    }
    private static long readNumeric(File f){try(BufferedReader br=new BufferedReader(new FileReader(f))){return numberFromObject(br.readLine());}catch(Exception e){return 0;}}
    private static boolean isPlausibleCapacity(long uah){return uah>=500000&&uah<=30000000;}
    private static long firstReadable(String name){File root=new File(POWER_SUPPLY_ROOT);File[] dirs=root.listFiles();if(dirs==null)return 0;for(File dir:dirs){if(!dir.isDirectory())continue;long v=readRaw(new File(dir,name).getAbsolutePath());if(v>0)return v;}return 0;}
    private static String firstReadableSource(String name){File root=new File(POWER_SUPPLY_ROOT);File[] dirs=root.listFiles();if(dirs==null)return "Unavailable";for(File dir:dirs){if(!dir.isDirectory())continue;File f=new File(dir,name);if(readRaw(f.getAbsolutePath())>0)return f.getAbsolutePath();}return "Unavailable";}
    private static long readRaw(String path){File f=new File(path);if(!f.isFile()||!f.canRead())return 0;try(BufferedReader br=new BufferedReader(new FileReader(f))){String s=br.readLine();return s==null?0:numberFromObject(s.trim());}catch(Exception e){return 0;}}
    private static long normalizeCapacity(long v){return v>0&&v<100000?v*1000:v;}
    private static final class SourceValue{final long value;final String source;SourceValue(long v,String s){value=v;source=s;}}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}

    static final class Snapshot{
        final double wear,health,estimatedWear,estimatedHealth;final long fullUah,designUah,fullEnergyUwh,designEnergyUwh,estimatedFullUah,updated;final int qualifiedSessions;
        final String fullSource,designSource,fullEnergySource,designEnergySource;
        Snapshot(double w,double h,long f,long d,long fe,long de,double ew,double eh,long ef,int q,long u,String fs,String ds,String fes,String des){wear=w;health=h;fullUah=f;designUah=d;fullEnergyUwh=fe;designEnergyUwh=de;estimatedWear=ew;estimatedHealth=eh;estimatedFullUah=ef;qualifiedSessions=q;updated=u;fullSource=fs;designSource=ds;fullEnergySource=fes;designEnergySource=des;}
        boolean measuredCapacityAvailable(){return fullUah>0&&designUah>0;}
        boolean measuredEnergyAvailable(){return fullEnergyUwh>0&&designEnergyUwh>0;}
        boolean estimatedCapacityAvailable(){return estimatedFullUah>0;}
        boolean available(){return measuredCapacityAvailable()||measuredEnergyAvailable()||estimatedCapacityAvailable();}
        double designCapacityMah(){return designUah>0?designUah/1000.0:0;}
        double fullCapacityMah(){return fullUah>0?fullUah/1000.0:0;}
        double estimatedCapacityMah(){return estimatedFullUah>0?estimatedFullUah/1000.0:0;}
        String capacityText(){if(measuredCapacityAvailable())return String.format(java.util.Locale.US,"%.0f / %.0f mAh",fullCapacityMah(),designCapacityMah());if(measuredEnergyAvailable())return String.format(java.util.Locale.US,"%.0f / %.0f mWh measured",fullEnergyUwh/1000.0,designEnergyUwh/1000.0);if(estimatedCapacityAvailable())return String.format(java.util.Locale.US,"~%.0f mAh estimated",estimatedCapacityMah());return "Unavailable";}
    }
}
