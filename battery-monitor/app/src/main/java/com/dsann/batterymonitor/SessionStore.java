package com.dsann.batterymonitor;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

final class SessionStore {
    private static final String PREFS="history", KEY_HISTORY="records", KEY_START="start",
            KEY_LAST="last", KEY_MAH="mah", KEY_CHARGING="charging",
            KEY_MIN_CURRENT="min_current", KEY_MAX_CURRENT="max_current",
            KEY_START_PERCENT="start_percent", KEY_LAST_PERCENT="last_percent",
            KEY_SCREEN_ON="screen_on", KEY_ON_MS="on_ms", KEY_ON_MAH="on_mah",
            KEY_ON_MIN="on_min", KEY_ON_MAX="on_max", KEY_ON_START_PERCENT="on_start_percent",
            KEY_ON_END_PERCENT="on_end_percent", KEY_OFF_MS="off_ms", KEY_OFF_MAH="off_mah",
            KEY_OFF_MIN="off_min", KEY_OFF_MAX="off_max", KEY_OFF_START_PERCENT="off_start_percent",
            KEY_OFF_END_PERCENT="off_end_percent";
    private static final long MAX_GAP=10*60*1000L;
    private SessionStore(){}

    static synchronized void sample(Context c, BatteryReader.Reading r){
        if(r.currentUa==Long.MIN_VALUE)return;
        long now=System.currentTimeMillis();
        boolean charging=isCharging(c);
        int percent=getBatteryPercent(c);
        boolean screenOn=isScreenOn(c);
        android.content.SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long start=p.getLong(KEY_START,0), last=p.getLong(KEY_LAST,0);
        boolean oldCharging=p.getBoolean(KEY_CHARGING,charging);
        boolean oldScreen=p.getBoolean(KEY_SCREEN_ON,screenOn);
        double mah=readDouble(p,KEY_MAH,0);
        double currentMa=r.currentUa/1000.0;
        double minCurrent=readDouble(p,KEY_MIN_CURRENT,Double.NaN);
        double maxCurrent=readDouble(p,KEY_MAX_CURRENT,Double.NaN);
        int startPercent=p.getInt(KEY_START_PERCENT,-1);
        int lastPercent=p.getInt(KEY_LAST_PERCENT,-1);

        long onMs=p.getLong(KEY_ON_MS,0), offMs=p.getLong(KEY_OFF_MS,0);
        double onMah=readDouble(p,KEY_ON_MAH,0), offMah=readDouble(p,KEY_OFF_MAH,0);
        double onMin=readDouble(p,KEY_ON_MIN,Double.NaN), onMax=readDouble(p,KEY_ON_MAX,Double.NaN);
        double offMin=readDouble(p,KEY_OFF_MIN,Double.NaN), offMax=readDouble(p,KEY_OFF_MAX,Double.NaN);
        int onStartPercent=p.getInt(KEY_ON_START_PERCENT,-1), onEndPercent=p.getInt(KEY_ON_END_PERCENT,-1);
        int offStartPercent=p.getInt(KEY_OFF_START_PERCENT,-1), offEndPercent=p.getInt(KEY_OFF_END_PERCENT,-1);

        if(start==0||last==0||now-last>MAX_GAP||oldCharging!=charging){
            if(start!=0&&last!=0){
                addRecord(p,start,last,oldCharging,mah,minCurrent,maxCurrent,startPercent,lastPercent,
                        onMs,onMah,onMin,onMax,onStartPercent,onEndPercent,
                        offMs,offMah,offMin,offMax,offStartPercent,offEndPercent);
            }
            start=last=now;
            mah=0;
            minCurrent=maxCurrent=currentMa;
            startPercent=percent;
            oldScreen=screenOn;
            onMs=offMs=0; onMah=offMah=0;
            onMin=onMax=offMin=offMax=Double.NaN;
            onStartPercent=onEndPercent=offStartPercent=offEndPercent=-1;
        } else {
            long elapsed=Math.max(0,now-last);
            double segmentMah=Math.abs(currentMa)*(elapsed/3600000.0);
            mah+=segmentMah;
            if(oldScreen){
                onMs+=elapsed; onMah+=segmentMah;
                if(Double.isNaN(onMin)){onMin=onMax=currentMa;onStartPercent=lastPercent;}
                onMin=Math.min(onMin,currentMa); onMax=Math.max(onMax,currentMa);
                if(percent>=0)onEndPercent=percent;
            }else{
                offMs+=elapsed; offMah+=segmentMah;
                if(Double.isNaN(offMin)){offMin=offMax=currentMa;offStartPercent=lastPercent;}
                offMin=Math.min(offMin,currentMa); offMax=Math.max(offMax,currentMa);
                if(percent>=0)offEndPercent=percent;
            }
            last=now;
            if(Double.isNaN(minCurrent)){minCurrent=maxCurrent=currentMa;}
            else {minCurrent=Math.min(minCurrent,currentMa);maxCurrent=Math.max(maxCurrent,currentMa);}
        }
        if(percent>=0)lastPercent=percent;
        if(oldScreen!=screenOn && percent>=0){
            if(screenOn)onStartPercent=percent; else offStartPercent=percent;
        }

        p.edit().putLong(KEY_START,start).putLong(KEY_LAST,last)
                .putLong(KEY_MAH,Double.doubleToLongBits(mah))
                .putLong(KEY_MIN_CURRENT,Double.doubleToLongBits(minCurrent))
                .putLong(KEY_MAX_CURRENT,Double.doubleToLongBits(maxCurrent))
                .putInt(KEY_START_PERCENT,startPercent).putInt(KEY_LAST_PERCENT,lastPercent)
                .putBoolean(KEY_CHARGING,charging).putBoolean(KEY_SCREEN_ON,screenOn)
                .putLong(KEY_ON_MS,onMs).putLong(KEY_OFF_MS,offMs)
                .putLong(KEY_ON_MAH,Double.doubleToLongBits(onMah)).putLong(KEY_OFF_MAH,Double.doubleToLongBits(offMah))
                .putLong(KEY_ON_MIN,Double.doubleToLongBits(onMin)).putLong(KEY_ON_MAX,Double.doubleToLongBits(onMax))
                .putLong(KEY_OFF_MIN,Double.doubleToLongBits(offMin)).putLong(KEY_OFF_MAX,Double.doubleToLongBits(offMax))
                .putInt(KEY_ON_START_PERCENT,onStartPercent).putInt(KEY_ON_END_PERCENT,onEndPercent)
                .putInt(KEY_OFF_START_PERCENT,offStartPercent).putInt(KEY_OFF_END_PERCENT,offEndPercent)
                .apply();
    }

    static synchronized void finish(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long start=p.getLong(KEY_START,0), last=p.getLong(KEY_LAST,0);
        if(start==0||last==0)return;
        boolean charging=p.getBoolean(KEY_CHARGING,false);
        double mah=readDouble(p,KEY_MAH,0);
        double minCurrent=readDouble(p,KEY_MIN_CURRENT,Double.NaN);
        double maxCurrent=readDouble(p,KEY_MAX_CURRENT,Double.NaN);
        int startPercent=p.getInt(KEY_START_PERCENT,-1), endPercent=p.getInt(KEY_LAST_PERCENT,-1);
        addRecord(p,start,last,charging,mah,minCurrent,maxCurrent,startPercent,endPercent,
                p.getLong(KEY_ON_MS,0),readDouble(p,KEY_ON_MAH,0),readDouble(p,KEY_ON_MIN,Double.NaN),readDouble(p,KEY_ON_MAX,Double.NaN),
                p.getInt(KEY_ON_START_PERCENT,-1),p.getInt(KEY_ON_END_PERCENT,-1),
                p.getLong(KEY_OFF_MS,0),readDouble(p,KEY_OFF_MAH,0),readDouble(p,KEY_OFF_MIN,Double.NaN),readDouble(p,KEY_OFF_MAX,Double.NaN),
                p.getInt(KEY_OFF_START_PERCENT,-1),p.getInt(KEY_OFF_END_PERCENT,-1));
        p.edit().remove(KEY_START).remove(KEY_LAST).remove(KEY_MAH).remove(KEY_MIN_CURRENT).remove(KEY_MAX_CURRENT)\n                .remove(KEY_START_PERCENT).remove(KEY_LAST_PERCENT).remove(KEY_CHARGING).remove(KEY_SCREEN_ON)\n                .remove(KEY_ON_MS).remove(KEY_ON_MAH).remove(KEY_ON_MIN).remove(KEY_ON_MAX).remove(KEY_ON_START_PERCENT).remove(KEY_ON_END_PERCENT)\n                .remove(KEY_OFF_MS).remove(KEY_OFF_MAH).remove(KEY_OFF_MIN).remove(KEY_OFF_MAX).remove(KEY_OFF_START_PERCENT).remove(KEY_OFF_END_PERCENT).apply();
    }

    static synchronized ArrayList<Record> getRecords(Context c){
        String raw=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_HISTORY,"");
        ArrayList<Record> out=new ArrayList<>();
        if(raw==null||raw.isEmpty())return out;
        String[] lines=raw.split("\\n");
        for(int i=lines.length-1;i>=0;i--){
            String[] x=lines[i].split("\\|");
            if(x.length!=4&&x.length!=8&&x.length!=20)continue;
            try{
                if(x.length==4) out.add(new Record(Long.parseLong(x[0]),Long.parseLong(x[1]),"1".equals(x[2]),Double.parseDouble(x[3]),
                        Double.NaN,Double.NaN,-1,-1,0,0,Double.NaN,Double.NaN,-1,-1,0,0,Double.NaN,Double.NaN,-1,-1));
                else if(x.length==8) out.add(new Record(Long.parseLong(x[0]),Long.parseLong(x[1]),"1".equals(x[2]),Double.parseDouble(x[3]),
                        Double.parseDouble(x[5]),Double.parseDouble(x[4]),Integer.parseInt(x[6]),Integer.parseInt(x[7]),
                        0,0,Double.NaN,Double.NaN,-1,-1,0,0,Double.NaN,Double.NaN,-1,-1));
                else out.add(new Record(Long.parseLong(x[0]),Long.parseLong(x[1]),"1".equals(x[2]),Double.parseDouble(x[3]),
                        Double.parseDouble(x[5]),Double.parseDouble(x[4]),Integer.parseInt(x[6]),Integer.parseInt(x[7]),
                        Long.parseLong(x[8]),Double.parseDouble(x[9]),Double.parseDouble(x[10]),Double.parseDouble(x[11]),Integer.parseInt(x[12]),Integer.parseInt(x[13]),
                        Long.parseLong(x[14]),Double.parseDouble(x[15]),Double.parseDouble(x[16]),Double.parseDouble(x[17]),Integer.parseInt(x[18]),Integer.parseInt(x[19])));
            }catch(Exception ignored){}
        }
        return out;
    }

    static synchronized void clear(Context c){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();}

    private static void addRecord(android.content.SharedPreferences p,long start,long end,boolean charging,double mah,double minCurrent,double maxCurrent,
                                  int startPercent,int endPercent,long onMs,double onMah,double onMin,double onMax,int onStartPercent,int onEndPercent,
                                  long offMs,double offMah,double offMin,double offMax,int offStartPercent,int offEndPercent){
        if(end<=start||mah<0.01)return;
        String old=p.getString(KEY_HISTORY,"");
        String rec=start+"|"+end+"|"+(charging?"1":"0")+"|"+String.format(Locale.US,"%.1f",mah)+"|"+
                String.format(Locale.US,"%.1f",maxCurrent)+"|"+String.format(Locale.US,"%.1f",minCurrent)+"|"+startPercent+"|"+endPercent+"|"+
                onMs+"|"+String.format(Locale.US,"%.1f",onMah)+"|"+String.format(Locale.US,"%.1f",onMin)+"|"+String.format(Locale.US,"%.1f",onMax)+"|"+onStartPercent+"|"+onEndPercent+"|"+
                offMs+"|"+String.format(Locale.US,"%.1f",offMah)+"|"+String.format(Locale.US,"%.1f",offMin)+"|"+String.format(Locale.US,"%.1f",offMax)+"|"+offStartPercent+"|"+offEndPercent;
        String s=(old==null||old.isEmpty())?rec:old+"\n"+rec;
        String[] a=s.split("\\n");
        if(a.length>100){StringBuilder b=new StringBuilder();for(int i=a.length-100;i<a.length;i++){if(b.length()>0)b.append('\n');b.append(a[i]);}s=b.toString();}
        p.edit().putString(KEY_HISTORY,s).apply();
    }

    private static double readDouble(android.content.SharedPreferences p,String key,double fallback){
        return Double.longBitsToDouble(p.getLong(key,Double.doubleToLongBits(fallback)));
    }
    private static int getBatteryPercent(Context c){
        Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i==null)return -1; int level=i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1),scale=i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100);
        return level<0||scale<=0?-1:Math.round(level*100f/scale);
    }
    private static boolean isCharging(Context c){
        Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return i!=null&&i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0)!=0;
    }
    private static boolean isScreenOn(Context c){
        PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
        return pm==null||pm.isInteractive();
    }

    static final class Record{
        final long start,end; final boolean charging; final double mah,minCurrent,maxCurrent;
        final int startPercent,endPercent; final long onMs,offMs; final double onMah,onMin,onMax,offMah,offMin,offMax;
        final int onStartPercent,onEndPercent,offStartPercent,offEndPercent;
        Record(long s,long e,boolean c,double m,double min,double max,int sp,int ep,long oms,double oma,double omin,double omax,int osp,int oep,
               long fms,double fma,double fmin,double fmax,int fsp,int fep){
            start=s;end=e;charging=c;mah=m;minCurrent=min;maxCurrent=max;startPercent=sp;endPercent=ep;
            onMs=oms;onMah=oma;onMin=omin;onMax=omax;onStartPercent=osp;onEndPercent=oep;
            offMs=fms;offMah=fma;offMin=fmin;offMax=fmax;offStartPercent=fsp;offEndPercent=fep;
        }
        String date(){return new SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.US).format(new Date(start));}
        String duration(){long m=Math.max(1,(end-start)/60000);return m>=60?(m/60)+"h "+(m%60)+"m":m+"m";}
    }
}