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
import android.Manifest;
import android.content.pm.PackageManager;
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
    private LinearLayout monitorView,historyView,healthView,historyList;
    private View healthContainer;
    private View monitorContainer;
    private TextView monitorTab,historyTab,healthTab;
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
        TextView appTitle=text("BatteryPulse",27);
        appTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(appTitle,full());
        TextView subtitle=text("Battery intelligence • power • health",13);
        subtitle.setTextColor(0xFF9AA0AA);
        header.addView(subtitle,top(2));
        root.addView(header);
        
        LinearLayout tabs=new LinearLayout(this);
        tabs.setPadding(dp(16),dp(4),dp(16),dp(10));
        monitorTab=tab("Monitor");
        historyTab=tab("History");
        healthTab=tab("Health");
        tabs.addView(monitorTab,new LinearLayout.LayoutParams(0,dp(42),1));
        tabs.addView(historyTab,new LinearLayout.LayoutParams(0,dp(42),1));
        tabs.addView(healthTab,new LinearLayout.LayoutParams(0,dp(42),1));
        root.addView(tabs);
        
        monitorView=buildMonitor();
        monitorContainer=new ScrollView(this);
        ((ScrollView)monitorContainer).setFillViewport(true);
        ((ScrollView)monitorContainer).addView(monitorView);
        historyView=buildHistory();
        healthView=buildHealth();
        healthContainer=new ScrollView(this);
        ((ScrollView)healthContainer).setFillViewport(true);
        ((ScrollView)healthContainer).addView(healthView);
        
        root.addView(monitorContainer,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(historyView,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(healthContainer,new LinearLayout.LayoutParams(-1,0,1));
        historyView.setVisibility(View.GONE);
        healthContainer.setVisibility(View.GONE);
        selectTab(0);
        
        monitorTab.setOnClickListener(v->{monitorContainer.setVisibility(View.VISIBLE);historyView.setVisibility(View.GONE);healthContainer.setVisibility(View.GONE);selectTab(0);});
        historyTab.setOnClickListener(v->{monitorContainer.setVisibility(View.GONE);historyView.setVisibility(View.VISIBLE);healthContainer.setVisibility(View.GONE);selectTab(1);refreshHistory();handler.removeCallbacks(historyUpdater);handler.postDelayed(historyUpdater,10000);});
        healthTab.setOnClickListener(v->{monitorContainer.setVisibility(View.GONE);historyView.setVisibility(View.GONE);healthContainer.setVisibility(View.VISIBLE);selectTab(2);refreshHealth();handler.removeCallbacks(historyUpdater);});
        
        setContentView(root);
        if (notificationSwitch.isChecked()) startNotificationService();
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }
    
    private LinearLayout buildMonitor(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16),dp(6),dp(16),dp(24));
        
        LinearLayout metrics=new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        
        LinearLayout voltageCard=metricCard("VOLTAGE","-- V",0xFF172334,0xFF63B3FF);
        voltage=(TextView)voltageCard.getTag();
        LinearLayout currentCard=metricCard("CURRENT","-- mA",0xFF17271F,0xFF63E6A8);
        current=(TextView)currentCard.getTag();
        metrics.addView(voltageCard,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams curLp=new LinearLayout.LayoutParams(0,-2,1);
        curLp.leftMargin=dp(8);
        metrics.addView(currentCard,curLp);
        r.addView(metrics);
        
        LinearLayout wearCard=card(0xFF201B2B,0xFFB58CFF);
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
        
        LinearLayout recording=card(0xFF211E18,0xFFFFC86B);
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

        TextView credit=text("{made by Debarghya Sannigrahi}",11);
        credit.setTextColor(0xFF68717E);
        credit.setGravity(Gravity.CENTER);
        credit.setLetterSpacing(0.03f);
        r.addView(credit,top(18));
        
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
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
                } else {
                    startNotificationService();
                }
            }else { SessionStore.finish(this); stopService(new Intent(this,BatteryNotificationService.class)); }
        });
        return r;
    }
    
    private LinearLayout buildHistory(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16),dp(4),dp(16),dp(16));
        TextView title=text("History",25);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        r.addView(title,full());
        TextView sub=text("A clear timeline of every recorded charging and usage session.",13);
        sub.setTextColor(0xFF707070);
        r.addView(sub,top(2));
        Button clear=actionButton("Clear older");
        r.addView(clear,top(10));
        clear.setOnClickListener(v->{SessionStore.clear(this);refreshHistory();});
        ScrollView s=new ScrollView(this);
        historyList=new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        s.addView(historyList);
        r.addView(s,new LinearLayout.LayoutParams(-1,0,1));
        return r;
    }
    
    private LinearLayout buildHealth(){
        LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(dp(16),dp(4),dp(16),dp(20));
        TextView title=text("Battery health",22); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); title.setMaxLines(1); r.addView(title,full());
        TextView sub=text("Measured fuel-gauge data • session-based estimates • source status",13); sub.setTextColor(0xFF9AA0AA); sub.setMaxLines(2); r.addView(sub,top(2));

        LinearLayout summary=card(0xFF201B2B,0xFFB58CFF);
        TextView big=text("--",30); big.setTypeface(Typeface.DEFAULT,Typeface.BOLD); big.setTextColor(0xFFBFA5FF); big.setTag("health_big"); summary.addView(big,full());
        TextView sm=text("Waiting for battery data",13); sm.setTextColor(0xFF9AA0AA); sm.setTag("health_summary"); summary.addView(sm,top(2));
        r.addView(summary,top(12));

        LinearLayout data=card(0xFF172334,0xFF63B3FF); data.setTag("health_data");
        r.addView(data,top(10));
        TextView q=sectionTitle("Measured battery data"); data.addView(q,full());
        addHealthRow(data,"Design capacity","--","design");

        LinearLayout est=card(0xFF17271F,0xFF63E6A8); est.setTag("health_est");
        r.addView(est,top(10)); est.addView(sectionTitle("Session-based estimate"),full());
        addHealthRow(est,"Estimated capacity","--","estcap"); addHealthRow(est,"Estimated health","--","esthealth");
        addHealthRow(est,"Estimated wear","--","estwear"); addHealthRow(est,"Qualified sessions","--","qualified");
        addHealthRow(est,"Qualification threshold","40% battery gain","threshold");

        LinearLayout model=card(0xFF211E18,0xFFFFC86B); model.addView(sectionTitle("Live / model data"),full());
        addHealthRow(model,"Battery level","--","level"); addHealthRow(model,"Voltage","--","volt"); addHealthRow(model,"Current","--","current");
        addHealthRow(model,"Wear model","Voltage-based estimate","model"); addHealthRow(model,"Last update","--","updated");
        r.addView(model,top(10));

        TextView note=text("Session health uses only charging sessions with at least 40 percentage points of battery gain. Up to the newest 8 valid sessions are used; a median-heavy estimate reduces the impact of noisy sessions. The voltage wear curve is an approximation, not AccuBattery's private algorithm.",11);
        note.setTextColor(0xFF858D99); r.addView(note,top(12));
        return r;
    }

    private TextView sectionTitle(String s){TextView v=text(s,15);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void addHealthRow(LinearLayout p,String label,String value,String tag){
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l=text(label,12); l.setTextColor(0xFF9AA0AA); row.addView(l,new LinearLayout.LayoutParams(0,-2,1));
        TextView v=text(value,13); v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); v.setGravity(Gravity.RIGHT); v.setTag(tag); row.addView(v,new LinearLayout.LayoutParams(-2,-2));
        p.addView(row,top(8));
    }
    private TextView healthValue(String tag){ return findTagged(healthView,tag); }
    private TextView findTagged(View root,String tag){
        if(root instanceof TextView && tag.equals(root.getTag())) return (TextView)root;
        if(root instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView v=findTagged(g.getChildAt(i),tag);if(v!=null)return v;}}
        return null;
    }
    private void refreshHealth(){
        if(healthView==null)return;
        BatteryWearStore.update(this); BatteryWearStore.updateEstimatedHealth(this);
        BatteryWearStore.Snapshot w=BatteryWearStore.get(this);
        TextView big=healthValue("health_big"), summary=healthValue("health_summary");

        if(w.measuredCapacityAvailable()){
            big.setText(String.format(java.util.Locale.US,"%.1f%% health",w.health));
            summary.setText(String.format(java.util.Locale.US,"Measured • %.1f%% wear  •  %.0f / %.0f mAh",w.wear,w.fullCapacityMah(),w.designCapacityMah()));
        }else if(w.measuredEnergyAvailable()){
            big.setText(String.format(java.util.Locale.US,"%.1f%% health",w.health));
            summary.setText(String.format(java.util.Locale.US,"Measured energy • %.1f%% wear  •  %.0f / %.0f mWh",w.wear,w.fullEnergyUwh/1000.0,w.designEnergyUwh/1000.0));
        }else if(w.estimatedHealth>=0){
            big.setText(String.format(java.util.Locale.US,"%.1f%% estimated",w.estimatedHealth));
            summary.setText(String.format(java.util.Locale.US,"Estimated from %d qualified charging session%s • %.0f mAh",w.qualifiedSessions,w.qualifiedSessions==1?"":"s",w.estimatedCapacityMah()));
        }else if(w.estimatedCapacityAvailable()){
            big.setText(String.format(java.util.Locale.US,"~%.0f mAh",w.estimatedCapacityMah()));
            summary.setText(String.format(java.util.Locale.US,"Estimated capacity • %d qualified charging session%s • measured capacity unavailable",w.qualifiedSessions,w.qualifiedSessions==1?"":"s"));
        }else{
            big.setText("--");
            summary.setText("No measured or session-based capacity available yet");
        }

        healthValue("design").setText(w.designUah>0?String.format(java.util.Locale.US,"%.0f mAh",w.designCapacityMah()):"Unavailable");
        healthValue("estcap").setText(w.estimatedCapacityAvailable()?String.format(java.util.Locale.US,"~%.0f mAh",w.estimatedCapacityMah()):"Need a 40%+ session");
        healthValue("esthealth").setText(w.estimatedHealth>=0?String.format(java.util.Locale.US,"%.1f%%",w.estimatedHealth):w.estimatedCapacityAvailable()?"No design capacity source":"Need more data");
        healthValue("estwear").setText(w.estimatedWear>=0?String.format(java.util.Locale.US,"%.1f%%",w.estimatedWear):w.estimatedCapacityAvailable()?"No design capacity source":"Need more data");
        healthValue("qualified").setText(String.valueOf(w.qualifiedSessions));

        BatteryReader.Reading br=BatteryReader.read(this);
        int level=getBatteryPercent();
        healthValue("level").setText(level>=0?String.valueOf(level)+"%":"Unavailable");
        healthValue("volt").setText(br.voltageText());
        healthValue("current").setText(br.currentText());
        healthValue("updated").setText(w.updated>0?new java.text.SimpleDateFormat("dd MMM, HH:mm:ss",java.util.Locale.US).format(new java.util.Date(w.updated)):"--");
    }
    private int getBatteryPercent(){Intent i=registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(i==null)return -1;int l=i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1),s=i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100);return l<0?-1:Math.round(l*100f/s);}

    private final Runnable historyUpdater=new Runnable(){public void run(){if(historyView!=null&&historyView.getVisibility()==View.VISIBLE){refreshHistory();handler.postDelayed(this,10000);}}};
    
    @Override protected void onResume(){
        super.onResume();
        updateValues();
        handler.removeCallbacks(updater);
        handler.post(updater);
        handler.removeCallbacks(historyUpdater);
        if(historyView!=null&&historyView.getVisibility()==View.VISIBLE){refreshHistory();handler.postDelayed(historyUpdater,10000);} if(healthContainer!=null&&healthContainer.getVisibility()==View.VISIBLE)refreshHealth();
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
            BatteryWearStore.updateEstimatedHealth(this);
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
            TextView empty=text("No sessions recorded yet",16);
            empty.setTextColor(0xFF8E96A3);
            historyList.addView(empty,top(24));
            return;
        }
        for(SessionStore.Record rec:rs){
            int accent=rec.charging?0xFF63E6A8:0xFFB58CFF;
            int surface=rec.charging?0xFF17271F:0xFF211B2D;
            LinearLayout card=card(surface,accent);
            TextView title=text((rec.charging?"CHARGED":"USED")+"  "+String.format(java.util.Locale.US,"%.1f mAh",rec.mah),18);
            title.setTextColor(accent);
            title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            card.addView(title,full());
            TextView details=text(rec.date()+"  •  "+rec.duration()+"  •  Screen ON: "+rec.screenOnDuration(),13);
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
            if(rec.charging) addChargeHealthDetails(expanded,rec);
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
    
    private void addChargeHealthDetails(LinearLayout parent,SessionStore.Record rec){
        int delta=rec.endPercent-rec.startPercent;
        TextView heading=text("Charging health",14);
        heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        parent.addView(heading,top(10));

        if(delta<1||rec.mah<0.1){
            parent.addView(text("Not enough charging data",12),top(2));
            return;
        }

        double estimatedCapacity=rec.mah*100.0/delta;
        BatteryWearStore.Snapshot raw=BatteryWearStore.get(this);
        double design=raw.designCapacityMah();
        double health=design>0?estimatedCapacity*100.0/design:-1;
        double wear=health>=0?Math.max(0,100-health):-1;

        // AccuBattery's public definition: efficiency = amount charged / charge-cycle wear.
        // We use a lightweight SOC-based approximation because the proprietary voltage wear
        // curve is not publicly specified in full.
        double wearCycles=BatteryWearStore.wearCycles(rec.startPercent,rec.endPercent);
        double efficiency=BatteryWearStore.chargeEfficiency(rec.startPercent,rec.endPercent);

        parent.addView(text("Estimated capacity: "+String.format(java.util.Locale.US,"%.0f mAh",estimatedCapacity)
                +(design>0?"  •  Design: "+String.format(java.util.Locale.US,"%.0f mAh",design):""),12),top(2));
        parent.addView(text((health>=0?"Health: "+String.format(java.util.Locale.US,"%.1f%%",health)
                +"  •  Wear: "+String.format(java.util.Locale.US,"%.1f%%",wear)
                +"  •  Efficiency: "+String.format(java.util.Locale.US,"%.0f%%",efficiency)
                +"  •  Wear cycles: "+String.format(java.util.Locale.US,"%.2f",wearCycles)
                :"Health: --  •  Wear: --  •  Efficiency: "+String.format(java.util.Locale.US,"%.0f%%",efficiency)
                +"  •  Wear cycles: "+String.format(java.util.Locale.US,"%.2f",wearCycles)),12),top(2));
        if(delta<BatteryWearStore.healthQualificationPercent()){
            TextView note=text("Short charge — excluded from long-term health average (<40%)",11);
            note.setTextColor(0xFF7E8794);
            parent.addView(note,top(3));
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
    
    private LinearLayout metricCard(String label,String value,int color,int strokeColor){
        LinearLayout c=card(color,strokeColor);
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

    private LinearLayout card(int color,int strokeColor){
        LinearLayout c=card(color);
        GradientDrawable bg=(GradientDrawable)c.getBackground();
        bg.setStroke(dp(1),strokeColor);
        return c;
    }

    private LinearLayout card(int color){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14),dp(13),dp(14),dp(13));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color == Color.WHITE ? 0xFF171A20 : color);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1),0xFF303744);
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
    
    private void selectTab(int selected){
        TextView[] tabs={monitorTab,historyTab,healthTab};
        int[] activeText={0xFF7CC7FF,0xFFB58CFF,0xFF63E6A8};
        int[] activeBg={0xFF172334,0xFF211B2D,0xFF17271F};
        for(int i=0;i<tabs.length;i++){
            boolean active=i==selected;
            tabs[i].setTextColor(active?activeText[i]:0xFF8E96A3);
            tabs[i].setBackground(round(active?activeBg[i]:0x00000000,12));
            tabs[i].setMinHeight(dp(48));
            tabs[i].setContentDescription(tabs[i].getText()+" tab"+(active?", selected":""));
        }
    }
    
    private GradientDrawable round(int color,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    
    private String formatSigned(double value){return String.format(java.util.Locale.US,"%+.1f",value);}
    private int indexForInterval(long ms){int best=0;long diff=Math.abs(INTERVALS_MS[0]-ms);for(int i=1;i<INTERVALS_MS.length;i++){long d=Math.abs(INTERVALS_MS[i]-ms);if(d<diff){diff=d;best=i;}}return best;}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==1001){
            if(Build.VERSION.SDK_INT<33 || (grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED)){
                startNotificationService();
            }else{
                notificationSwitch.setChecked(false);
                getSharedPreferences(PREFS,0).edit().putBoolean("notification_enabled",false).apply();
                samplingBar.setEnabled(false);
            }
        }
    }

    private void startNotificationService(){Intent i=new Intent(this,BatteryNotificationService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(0xFFE8EBF0);return v;}
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=full();p.topMargin=dp(n);return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
