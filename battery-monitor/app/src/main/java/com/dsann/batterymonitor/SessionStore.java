package com.dsann.batterymonitor;

import android.content.Context;
import android.content.Intent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

final class SessionStore {
    private static final String PREFS="history", KEY_HISTORY="records", KEY_START="start",
            KEY_LAST="last", KEY_MAH="mah", KEY_CHARGING="charging",
            KEY_MIN_CURRENT="min_current", KEY_MAX_CURRENT="max_current",
            KEY_START_PERCENT="start_percent", KEY_LAST_PERCENT="last_percent";
    private static final long MAX_GAP=10*60*1000L;
    private SessionStore(){}

    static synchronized void sample(Context c, BatteryReader.Reading r){
        if(r.currentUa==Long.MIN_VALUE)return;
        long now=System.currentTimeMillis();
        boolean charging=isCharging(c);
        int percent=getBatteryPercent(c);
        android.content.SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long start=p.getLong(KEY_START,0), last=p.getLong(KEY_LAST,0);
        boolean old=p.getBoolean(KEY_CHARGING,charging);
        double mah=Double.longBitsToDouble(p.getLong(KEY_MAH,Double.doubleToLongBits(0)));
        double currentMa=r.currentUa/1000.0;
        double minCurrent=Double.longBitsToDouble(p.getLong(KEY_MIN_CURRENT,Double.doubleToLongBits(Double.NaN)));
        double maxCurrent=Double.longBitsToDouble(p.getLong(KEY_MAX_CURRENT,Double.doubleToLongBits(Double.NaN)));
        int startPercent=p.getInt(KEY_START_PERCENT,-1);
        int lastPercent=p.getInt(KEY_LAST_PERCENT,-1);

        if(start==0||last==0||now-last>MAX_GAP||old!=charging){
            if(start!=0&&last!=0){
                addRecord(p,start,last,old,mah,minCurrent,maxCurrent,startPercent,lastPercent);
            }
            start=last=now;
            mah=0;
            minCurrent=maxCurrent=currentMa;
            startPercent=percent;
        }else{
            double ma=Math.abs(currentMa);
            mah+=ma*((now-last)/3600000.0);
            last=now;
            if(Double.isNaN(minCurrent)||Double.isNaN(maxCurrent)){
                minCurrent=maxCurrent=currentMa;
            }else{
                minCurrent=Math.min(minCurrent,currentMa);
                maxCurrent=Math.max(maxCurrent,currentMa);
            }
        }
        if(percent>=0)lastPercent=percent;

        p.edit().putLong(KEY_START,start).putLong(KEY_LAST,last)
                .putLong(KEY_MAH,Double.doubleToLongBits(mah))
                .putLong(KEY_MIN_CURRENT,Double.doubleToLongBits(minCurrent))
                .putLong(KEY_MAX_CURRENT,Double.doubleToLongBits(maxCurrent))
                .putInt(KEY_START_PERCENT,startPercent)
                .putInt(KEY_LAST_PERCENT,lastPercent)
                .putBoolean(KEY_CHARGING,charging).apply();
    }

    static synchronized void finish(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long start=p.getLong(KEY_START,0), last=p.getLong(KEY_LAST,0);
        if(start==0||last==0)return;
        boolean charging=p.getBoolean(KEY_CHARGING,false);
        double mah=Double.longBitsToDouble(p.getLong(KEY_MAH,Double.doubleToLongBits(0)));
        double minCurrent=Double.longBitsToDouble(p.getLong(KEY_MIN_CURRENT,Double.doubleToLongBits(Double.NaN)));
        double maxCurrent=Double.longBitsToDouble(p.getLong(KEY_MAX_CURRENT,Double.doubleToLongBits(Double.NaN)));
        int startPercent=p.getInt(KEY_START_PERCENT,-1);
        int endPercent=p.getInt(KEY_LAST_PERCENT,-1);
        addRecord(p,start,last,charging,mah,minCurrent,maxCurrent,startPercent,endPercent);
        p.edit().remove(KEY_START).remove(KEY_LAST).remove(KEY_MAH)
                .remove(KEY_MIN_CURRENT).remove(KEY_MAX_CURRENT)
                .remove(KEY_START_PERCENT).remove(KEY_LAST_PERCENT)
                .remove(KEY_CHARGING).apply();
    }

    static synchronized ArrayList<Record> getRecords(Context c){
        String raw=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_HISTORY,"");
        ArrayList<Record> out=new ArrayList<>();
        if(raw==null||raw.isEmpty())return out;
        String[] lines=raw.split("\\n");
        for(int i=lines.length-1;i>=0;i--){
            String[] x=lines[i].split("\\|");
            if(x.length!=4&&x.length!=8)continue;
            try{
                if(x.length==4){
                    out.add(new Record(Long.parseLong(x[0]),Long.parseLong(x[1]),
                            "1".equals(x[2]),Double.parseDouble(x[3]),
                            Double.NaN,Double.NaN,-1,-1));
                }else{
                    out.add(new Record(Long.parseLong(x[0]),Long.parseLong(x[1]),
                            "1".equals(x[2]),Double.parseDouble(x[3]),
                            Double.parseDouble(x[5]),Double.parseDouble(x[4]),
                            Integer.parseInt(x[6]),Integer.parseInt(x[7])));
                }
            }catch(Exception ignored){}
        }
        return out;
    }

    static synchronized void clear(Context c){
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
    }

    private static void addRecord(android.content.SharedPreferences p,long start,long end,
                                  boolean charging,double mah,double minCurrent,double maxCurrent,
                                  int startPercent,int endPercent){
        if(end<=start||mah<0.01)return;
        String old=p.getString(KEY_HISTORY,"");
        String rec=start+"|"+end+"|"+(charging?"1":"0")+"|"
                +String.format(Locale.US,"%.1f",mah)+"|"
                +String.format(Locale.US,"%.1f",maxCurrent)+"|"
                +String.format(Locale.US,"%.1f",minCurrent)+"|"
                +startPercent+"|"+endPercent;
        String s=(old==null||old.isEmpty())?rec:old+"\n"+rec;
        String[] a=s.split("\\n");
        if(a.length>100){
            StringBuilder b=new StringBuilder();
            for(int i=a.length-100;i<a.length;i++){
                if(b.length()>0)b.append('\n');
                b.append(a[i]);
            }
            s=b.toString();
        }
        p.edit().putString(KEY_HISTORY,s).apply();
    }

    private static int getBatteryPercent(Context c){
        Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i==null)return -1;
        int level=i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1);
        int scale=i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100);
        return level<0||scale<=0?-1:Math.round(level*100f/scale);
    }

    private static boolean isCharging(Context c){
        Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i==null)return false;
        return i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0)!=0;
    }

    static final class Record{
        final long start,end;
        final boolean charging;
        final double mah,minCurrent,maxCurrent;
        final int startPercent,endPercent;
        Record(long s,long e,boolean c,double m,double min,double max,int sp,int ep){
            start=s;end=e;charging=c;mah=m;minCurrent=min;maxCurrent=max;
            startPercent=sp;endPercent=ep;
        }
        String date(){return new SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.US).format(new Date(start));}
        String duration(){long m=Math.max(1,(end-start)/60000);return m>=60?(m/60)+"h "+(m%60)+"m":m+"m";}
    }
}
