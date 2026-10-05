package com.dsann.batterymonitor;

import android.content.Context;
import android.content.Intent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

final class SessionStore {
    private static final String PREFS="history", KEY_HISTORY="records", KEY_START="start",
            KEY_LAST="last", KEY_MAH="mah", KEY_CHARGING="charging";
    private static final long MAX_GAP=10*60*1000L;
    private SessionStore(){}

    static synchronized void sample(Context c, BatteryReader.Reading r){
        if(r.currentUa==Long.MIN_VALUE)return;
        long now=System.currentTimeMillis();
        boolean charging=isCharging(c);
        android.content.SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long start=p.getLong(KEY_START,0), last=p.getLong(KEY_LAST,0);
        boolean old=p.getBoolean(KEY_CHARGING,charging);
        double mah=Double.longBitsToDouble(p.getLong(KEY_MAH,Double.doubleToLongBits(0)));
        if(start==0||last==0||now-last>MAX_GAP||old!=charging){
            if(start!=0&&last!=0)addRecord(p,start,last,old,mah);
            start=last=now; mah=0;
        }else{
            double ma=Math.abs(r.currentUa/1000.0);
            mah+=ma*((now-last)/3600000.0);
            last=now;
        }
        p.edit().putLong(KEY_START,start).putLong(KEY_LAST,last)
                .putLong(KEY_MAH,Double.doubleToLongBits(mah))
                .putBoolean(KEY_CHARGING,charging).apply();
    }

    static synchronized void finish(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long start=p.getLong(KEY_START,0), last=p.getLong(KEY_LAST,0);
        if(start==0||last==0)return;
        boolean charging=p.getBoolean(KEY_CHARGING,false);
        double mah=Double.longBitsToDouble(p.getLong(KEY_MAH,Double.doubleToLongBits(0)));
        addRecord(p,start,last,charging,mah);
        p.edit().remove(KEY_START).remove(KEY_LAST).remove(KEY_MAH).remove(KEY_CHARGING).apply();
    }

    static synchronized ArrayList<Record> getRecords(Context c){
        String raw=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_HISTORY,"");
        ArrayList<Record> out=new ArrayList<>();
        if(raw==null||raw.isEmpty())return out;
        String[] lines=raw.split("\\n");
        for(int i=lines.length-1;i>=0;i--){
            String[] x=lines[i].split("\\|");
            if(x.length!=4)continue;
            try{out.add(new Record(Long.parseLong(x[0]),Long.parseLong(x[1]),
                    "1".equals(x[2]),Double.parseDouble(x[3])));}catch(Exception ignored){}
        }
        return out;
    }

    static synchronized void clear(Context c){
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
    }

    private static void addRecord(android.content.SharedPreferences p,long start,long end,
                                  boolean charging,double mah){
        if(end<=start||mah<0.01)return;
        String old=p.getString(KEY_HISTORY,"");
        String rec=start+"|"+end+"|"+(charging?"1":"0")+"|"+String.format(Locale.US,"%.1f",mah);
        String s=(old==null||old.isEmpty())?rec:old+"\n"+rec;
        String[] a=s.split("\\n");
        if(a.length>100){
            StringBuilder b=new StringBuilder();
            for(int i=a.length-100;i<a.length;i++){if(b.length()>0)b.append('\n');b.append(a[i]);}
            s=b.toString();
        }
        p.edit().putString(KEY_HISTORY,s).apply();
    }

    private static boolean isCharging(Context c){
        Intent i=c.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i==null)return false;
        return i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0)!=0;
    }

    static final class Record{
        final long start,end; final boolean charging; final double mah;
        Record(long s,long e,boolean c,double m){start=s;end=e;charging=c;mah=m;}
        String date(){return new SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.US).format(new Date(start));}
        String duration(){long m=Math.max(1,(end-start)/60000);return m>=60?(m/60)+"h "+(m%60)+"m":m+"m";}
    }
}