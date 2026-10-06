package com.dsann.batterymonitor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String PREFS="settings";
    private static final String KEY_INTERVAL_MS="sampling_interval_ms";
    private static final long[] INTERVALS_MS={500L,1000L,5000L,10000L,15000L,30000L};
    private static final String[] INTERVAL_LABELS={"0.5 s","1 s","5 s","10 s","15 s","30 s"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView voltage,current,wearValue,wearSub;
    private Switch notificationSwitch;
    private SeekBar samplingBar;
    private TextView samplingLabel;
    private LinearLayout monitorView,historyView,historyList;
    private View monitorContainer;
    private TextView monitorTab,historyTab;
    private final Runnable updater=new Runnable(){public void run(){updateValues();handler.postDelayed(this,1000);}};
    
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF101317);
        getWindow().setNavigationBarColor(0xFF101317);
        
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F1115);
        
        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20),dp(18),dp(20),dp(8));
        TextView appTitle=text("Battery Monitor",25);
        appTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(appTitle,full());
        TextView subtitle=text("Power usage, sessions and battery tools",13);
        subtitle.setTextColor(0xFF9AA0AA);
        header.addView(subtitle,top(2));
        root.addView(header);
        
        LinearLayout tabs=new LinearLayout(this);
        tabs.setPadding(dp(16),dp(4),dp(16),dp(10));
        monitorTab=tab("Monitor");
        historyTab=tab("History");
        tabs.addView(monitorTab,new LinearLayout.LayoutParams(0,dp(42),1));
        tabs.addView(historyTab,new LinearLayout.LayoutParams(0,dp(42),1));
        root.addView(tabs);
        
        monitorView=buildMonitor();
        monitorContainer=new ScrollView(this);
        ((ScrollView)monitorContainer).setFillViewport(true);
        ((ScrollView)monitorContainer).addView(monitorView);
        historyView=buildHistory();
        
        root.addView(monitorContainer,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(historyView,new LinearLayout.LayoutParams(-1,0,1));
        historyView.setVisibility(View.GONE);
        selectTab(true);
        
        monitorTab.setOnClickListener(v->{monitorContainer.setVisibility(View.VISIBLE);historyView.setVisibility(View.GONE);selectTab(true);});
        historyTab.setOnClickListener(v->{monitorContainer.setVisibility(View.GONE);historyView.setVisibility(View.VISIBLE);selectTab(false);refreshHistory();handler.removeCallbacks(historyUpdater);handler.postDelayed(historyUpdater,10000);});
        
        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }
    
    private LinearLayout buildMonitor(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16),dp(4),dp(16),dp(20));
        
        LinearLayout metrics=new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        
        LinearLayout voltageCard=metricCard("VOLTAGE","-- V",0xFF151C25);
        voltage=(TextView)voltageCard.getTag();
        LinearLayout currentCard=metricCard("CURRENT","-- mA",0xFF152019);
        current=(TextView)currentCard.getTag();
        metrics.addView(voltageCard,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams curLp=new LinearLayout.LayoutParams(0,-2,1);
        curLp.leftMargin=dp(8);
        metrics.addView(currentCard,curLp);
        r.addView(metrics);
        
        LinearLayout wearCard=card(0xFF171E1A);
        LinearLayout wearTop=new LinearLayout(this);
        wearTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView wearTitle=text("Battery wear",17);
        wearTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        wearTop.addView(wearTitle,new LinearLayout.LayoutParams(0,-2,1));
        wearValue=text("--",20);
        wearValue.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        wearValue.setTextColor(0xFF9BE7B0);
        wearTop.addView(wearValue,new LinearLayout.LayoutParams(-2,-2));
        wearCard.addView(wearTop,full());
        wearSub=text("Updates while charging • estimated from full/design capacity",12);
        wearSub.setTextColor(0xFF9AA0AA);
        wearCard.addView(wearSub,top(3));
        r.addView(wearCard,top(10));
        
        LinearLayout recording=card();
        LinearLayout recordingTop=new LinearLayout(this);
        recordingTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView recordingTitle=text("Background recording",17);
        recordingTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        recordingTop.addView(recordingTitle,new LinearLayout.LayoutParams(0,-2,1));
        notificationSwitch=new Switch(this);
        recordingTop.addView(notificationSwitch,new LinearLayout.LayoutParams(-2,-2));
        recording.addView(recordingTop,full());
        TextView recordingSub=text("Keeps the session updated while the app is closed.",13);
        recordingSub.setTextColor(0xFF9AA0AA);
        recording.addView(recordingSub,top(2));
        
        LinearLayout sampling=new LinearLayout(this);
        sampling.setOrientation(LinearLayout.VERTICAL);
        samplingLabel=text("Sampling interval  •  15 s",13);
        samplingLabel.setTextColor(0xFF9AA0AA);
        sampling.addView(samplingLabel,full());
        samplingBar=new SeekBar(this);
        samplingBar.setMax(INTERVALS_MS.length-1);
        sampling.addView(samplingBar,new LinearLayout.LayoutParams(-1,-2));
        recording.addView(sampling,top(10));
        r.addView(recording,top(12));
        
        android.content.SharedPreferences prefs=getSharedPreferences(PREFS,0);
        int index=indexForInterval(prefs.getLong(KEY_INTERVAL_MS,15000L));
        samplingBar.setProgress(index);
        samplingLabel.setText("Sampling interval  •  "+INTERVAL_LABELS[index]);
        notificationSwitch.setChecked(prefs.getBoolean("notification_enabled",false));
        samplingBar.setEnabled(notificationSwitch.isChecked());
        samplingBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){
                samplingLabel.setText("Sampling interval  •  "+INTERVAL_LABELS[progress]);
                if(fromUser)getSharedPreferences(PREFS,0).edit().putLong(KEY_INTERVAL_MS,INTERVALS_MS[progress]).apply();
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        
        notificationSwitch.setOnCheckedChangeListener((b,on)->{
            getSharedPreferences(PREFS,0).edit().putBoolean("notification_enabled",on).apply();
            samplingBar.setEnabled(on);
            if(on){
                startNotificationService();
            }else stopService(new Intent(this,BatteryNotificationService.class));
        });
        return r;
    }
    
    private LinearLayout buildHistory(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16),dp(4),dp(16),dp(16));
        TextView title=text("Session history",22);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        r.addView(title,full());
        TextView sub=text("Measured energy use across recording sessions.",13);
        sub.setTextColor(0xFF707070);
        r.addView(sub,top(2));
        Button clear=actionButton("Clear history");
        r.addView(clear,top(10));
        clear.setOnClickListener(v->{SessionStore.clear(this);refreshHistory();});
        ScrollView s=new ScrollView(this);
        historyList=new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        s.addView(historyList);
        r.addView(s,new LinearLayout.LayoutParams(-1,0,1));
        return r;
    }
    
    private final Runnable historyUpdater=new Runnable(){public void run(){if(historyView!=null&&historyView.getVisibility()==View.VISIBLE){refreshHistory();handler.postDelayed(this,10000);}}};
    
    @Override protected void onResume(){
        super.onResume();
        updateValues();
        handler.removeCallbacks(updater);
        handler.post(updater);
        handler.removeCallbacks(historyUpdater);
        if(historyView!=null&&historyView.getVisibility()==View.VISIBLE){refreshHistory();handler.postDelayed(historyUpdater,10000);}
    }
    
    @Override protected void onPause(){
        handler.removeCallbacks(updater);
        handler.removeCallbacks(historyUpdater);
        super.onPause();
    }
    
    private void updateValues(){
        try {
            BatteryReader.Reading r=BatteryReader.read(this);
            voltage.setText(r.voltageText());
            current.setText(r.currentText());
            BatteryWearStore.update(this);
            BatteryWearStore.Snapshot w=BatteryWearStore.get(this);
            if(w.available()){
                wearValue.setText(String.format(java.util.Locale.US,"%.1f%% wear",w.wear));
                String state=BatteryWearStore.isCharging(this)?"Charging • ":"Last estimate • ";
                wearSub.setText(state+String.format(java.util.Locale.US,"%.1f%% health",w.health)
                        +" • "+w.capacityText());
            }else{
                wearValue.setText("--");
                wearSub.setText(BatteryWearStore.isCharging(this)
                        ?"Charging • waiting for battery capacity data"
                        :"Charge the tablet to calculate battery wear");
            }
        } catch (Throwable ignored) {
            voltage.setText("N/A");
            current.setText("N/A");
        }
    }
    
    private void refreshHistory(){
        historyList.removeAllViews();
        ArrayList<SessionStore.Record> rs=SessionStore.getRecords(this);
        if(rs.isEmpty()){
            TextView empty=text("No sessions yet",16);
            empty.setTextColor(0xFF8E96A3);
            historyList.addView(empty,top(24));
            return;
        }
        for(SessionStore.Record rec:rs){
            LinearLayout card=card();
            TextView title=text((rec.charging?"CHARGED":"USED")+"  "+String.format(java.util.Locale.US,"%.1f mAh",rec.mah),18);
            title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            card.addView(title,full());
            TextView details=text(rec.date()+"  •  "+rec.duration(),13);
            details.setTextColor(0xFF9AA0AA);
            card.addView(details,top(3));
            String currentRange=Double.isNaN(rec.minCurrent)?"Current: --":"Current: min "+formatSigned(rec.minCurrent)+" mA  •  max "+formatSigned(rec.maxCurrent)+" mA";
            String percentRange=(rec.startPercent>=0&&rec.endPercent>=0)?"Battery: "+rec.startPercent+"% → "+rec.endPercent+"%  ("+String.format(java.util.Locale.US,"%+d%%",rec.endPercent-rec.startPercent)+")":"Battery: --";
            TextView overall=text("Overall",12);
            overall.setTextColor(0xFF8E96A3);
            card.addView(overall,top(12));
            card.addView(text(currentRange,13),top(3));
            card.addView(text(percentRange,13),top(3));
            
            LinearLayout expanded=new LinearLayout(this);
            expanded.setOrientation(LinearLayout.VERTICAL);
            expanded.setVisibility(View.GONE);
            addScreenDetails(expanded,"Screen ON",rec.onMs,rec.onMah,rec.onMin,rec.onMax,rec.onStartPercent,rec.onEndPercent);
            addScreenDetails(expanded,"Screen OFF",rec.offMs,rec.offMah,rec.offMin,rec.offMax,rec.offStartPercent,rec.offEndPercent);
            card.addView(expanded,top(10));
            TextView hint=text("Tap for screen details",11);
            hint.setTextColor(0xFF7E8794);
            card.addView(hint,top(8));
            card.setOnClickListener(v->{
                boolean show=expanded.getVisibility()!=View.VISIBLE;
                expanded.setVisibility(show?View.VISIBLE:View.GONE);
                hint.setText(show?"Tap to collapse":"Tap for screen details");
            });
            LinearLayout.LayoutParams cp=full();
            cp.topMargin=dp(10);
            historyList.addView(card,cp);
        }
    }
    
    private void addScreenDetails(LinearLayout parent,String label,long ms,double mah,double min,double max,int startPercent,int endPercent){
        TextView heading=text(label,14);
        heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        parent.addView(heading,top(4));
        if(ms<=0&&mah<=0){
            TextView none=text("No samples",12);
            none.setTextColor(0xFF7E8794);
            parent.addView(none,top(2));
            return;
        }
        long minutes=Math.max(1,ms/60000L);
        String duration=minutes>=60?(minutes/60)+"h "+(minutes%60)+"m":minutes+"m";
        parent.addView(text("Time: "+duration+"  •  mAh: "+String.format(java.util.Locale.US,"%.1f",mah),12),top(2));
        if(Double.isNaN(min)||Double.isNaN(max))parent.addView(text("Current: --",12),top(2));
        else parent.addView(text("Current: min "+formatSigned(min)+" mA  •  max "+formatSigned(max)+" mA",12),top(2));
        if(startPercent>=0&&endPercent>=0)parent.addView(text("Battery: "+startPercent+"% → "+endPercent+"%  ("+String.format(java.util.Locale.US,"%+d%%",endPercent-startPercent)+")",12),top(2));
    }
    
    private LinearLayout metricCard(String label,String value,int color){
        LinearLayout c=card(color);
        TextView l=text(label,11);
        l.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        l.setTextColor(0xFF8E96A3);
        c.addView(l,full());
        TextView v=text(value,25);
        v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        c.setTag(v);
        c.addView(v,top(5));
        return c;
    }
    
    private LinearLayout toolCard(String title,String subtitle){
        LinearLayout c=card(0xFFFEFCF6);
        TextView t=text(title,16);
        t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        c.addView(t,full());
        TextView s=text(subtitle,12);
        s.setTextColor(0xFF707070);
        c.addView(s,top(2));
        return c;
    }
    
    private LinearLayout card(){return card(Color.WHITE);}

    private LinearLayout card(int color){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14),dp(13),dp(14),dp(13));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color == Color.WHITE ? 0xFF171A20 : color);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1),0xFF2A3039);
        c.setBackground(bg);
        if(Build.VERSION.SDK_INT>=21)c.setElevation(dp(1));
        return c;
    }
    
    private Button actionButton(String label){
        Button b=new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }
    
    private TextView tab(String label){
        TextView v=text(label,14);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    
    private void selectTab(boolean monitor){
        monitorTab.setTextColor(monitor?0xFFF2F4F7:0xFF8E96A3);
        historyTab.setTextColor(monitor?0xFF8E96A3:0xFFF2F4F7);
        monitorTab.setBackground(round(monitor?0xFF242932:0x00000000,12));
        historyTab.setBackground(round(monitor?0x00000000:0xFF242932,12));
    }
    
    private GradientDrawable round(int color,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    
    private String formatSigned(double value){return String.format(java.util.Locale.US,"%+.1f",value);}
    private int indexForInterval(long ms){int best=0;long diff=Math.abs(INTERVALS_MS[0]-ms);for(int i=1;i<INTERVALS_MS.length;i++){long d=Math.abs(INTERVALS_MS[i]-ms);if(d<diff){diff=d;best=i;}}return best;}
    private void startNotificationService(){Intent i=new Intent(this,BatteryNotificationService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(0xFFE8EBF0);return v;}
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=full();p.topMargin=dp(n);return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
