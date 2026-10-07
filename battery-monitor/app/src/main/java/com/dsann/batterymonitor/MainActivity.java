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
    private TextView voltage,current;
    private Switch notificationSwitch;
    private SeekBar samplingBar;
    private TextView samplingLabel;
    private LinearLayout monitorView,historyView,historyList;
    private View monitorContainer;
    private TextView monitorTab,historyTab,healthTab;
    private LinearLayout healthView;
    private ScrollView healthScroll;
    private TextView healthLevel,healthStatus,healthTemp,healthVoltage,healthCurrent,healthSource,healthFullCharge,healthRemaining,healthDesign,healthRatio,healthRaw,healthFile,healthFullEnergy,healthDesignEnergy,healthSessionEstimate,healthSessionHealth;
    private final Runnable updater=new Runnable(){public void run(){updateValues();handler.postDelayed(this,1000);}};
    
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F1115);
        
        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20),dp(18),dp(20),dp(8));
        TextView appTitle=text("BatteryPulse",25);
        appTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(appTitle,full());
        TextView subtitle=text("Battery monitor • sessions • health",13);
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
        LinearLayout healthContent=buildHealth();
        healthScroll=new ScrollView(this);
        healthScroll.setFillViewport(true);
        healthScroll.setClipToPadding(false);
        healthScroll.setPadding(0,0,0,dp(8));
        healthScroll.addView(healthContent);
        healthView=healthContent;
        
        root.addView(monitorContainer,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(historyView,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(healthScroll,new LinearLayout.LayoutParams(-1,0,1));
        historyView.setVisibility(View.GONE);
        healthScroll.setVisibility(View.GONE);
        selectTab(0);
        monitorTab.setOnClickListener(v->showTab(0));
        historyTab.setOnClickListener(v->showTab(1));
        healthTab.setOnClickListener(v->showTab(2));
        
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
        
        LinearLayout voltageCard=metricCard("VOLTAGE","-- V",0xFF172334,0xFF63B3FF);
        voltage=(TextView)voltageCard.getTag();
        LinearLayout currentCard=metricCard("CURRENT","-- mA",0xFF17271F,0xFF63E6A8);
        current=(TextView)currentCard.getTag();
        metrics.addView(voltageCard,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams curLp=new LinearLayout.LayoutParams(0,-2,1);
        curLp.leftMargin=dp(8);
        metrics.addView(currentCard,curLp);
        r.addView(metrics);
        
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
        
        LinearLayout dozeBox=toolCard("Force Doze","Put Android into Doze for power testing.");
        LinearLayout dozeRow=new LinearLayout(this);
        dozeRow.setGravity(Gravity.CENTER_VERTICAL);
        Button enableDoze=actionButton("Enable");
        Button disableDoze=actionButton("Disable");
        dozeRow.addView(enableDoze,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(0,-2,1);
        dlp.leftMargin=dp(8);
        dozeRow.addView(disableDoze,dlp);
        dozeBox.addView(dozeRow,top(10));
        TextView dozeStatus=text("Normal idle behavior",12);
        dozeStatus.setTextColor(0xFF858D99);
        dozeBox.addView(dozeStatus,top(4));
        r.addView(dozeBox,top(12));
        
        enableDoze.setOnClickListener(v->{DozeController.Result result=DozeController.forceDoze();dozeStatus.setText(result.message);});
        disableDoze.setOnClickListener(v->{DozeController.Result result=DozeController.disableForceDoze();dozeStatus.setText(result.message);});
        
        LinearLayout percentBox=toolCard("Battery percentage override","Temporarily change Android's displayed battery level.");
        LinearLayout percentRow=new LinearLayout(this);
        percentRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText percentInput=new EditText(this);
        percentInput.setHint("0–100");
        percentInput.setSingleLine(true);
        percentInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        percentRow.addView(percentInput,new LinearLayout.LayoutParams(0,-2,1));
        Button setPercent=actionButton("Set");
        Button resetPercent=actionButton("Reset");
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-2,-2);
        slp.leftMargin=dp(6);
        percentRow.addView(setPercent,slp);
        LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-2,-2);
        rlp.leftMargin=dp(6);
        percentRow.addView(resetPercent,rlp);
        percentBox.addView(percentRow,top(10));
        TextView percentStatus=text("Reset restores the real battery value.",12);
        percentStatus.setTextColor(0xFF858D99);
        percentBox.addView(percentStatus,top(4));
        r.addView(percentBox,top(12));
        
        setPercent.setOnClickListener(v->{
            String value=percentInput.getText().toString().trim();
            if(value.isEmpty()){percentStatus.setText("Enter a value from 0 to 100.");return;}
            try{
                BatteryPercentOverride.Result result=BatteryPercentOverride.set(Integer.parseInt(value));
                percentStatus.setText(result.message);
            }catch(NumberFormatException e){percentStatus.setText("Enter a value from 0 to 100.");}
        });
        resetPercent.setOnClickListener(v->{BatteryPercentOverride.Result result=BatteryPercentOverride.reset();percentStatus.setText(result.message);});
        
        notificationSwitch.setOnCheckedChangeListener((b,on)->{
            getSharedPreferences(PREFS,0).edit().putBoolean("notification_enabled",on).apply();
            samplingBar.setEnabled(on);
            if(on){
                startNotificationService();
            }else { SessionStore.finish(this); stopService(new Intent(this,BatteryNotificationService.class)); }
        });
        return r;
    }
    
    private LinearLayout buildHealth(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16),dp(4),dp(16),dp(20));
        TextView title=text("Battery health",22); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); r.addView(title,full());
        TextView sub=text("Standard Android battery properties and Linux fuel-gauge data.",13); sub.setTextColor(0xFF707070); r.addView(sub,top(2));
        LinearLayout overview=card(0xFF201B2B,0xFFB58CFF);
        healthLevel=text("--%",30); healthLevel.setTypeface(Typeface.DEFAULT,Typeface.BOLD); overview.addView(healthLevel,full());
        healthStatus=text("Status: --",14); healthStatus.setTextColor(0xFF9AA0AA); overview.addView(healthStatus,top(4));
        r.addView(overview,top(12));
        addHealthField(r,"Full charge capacity","healthFullCharge");
        addHealthField(r,"Remaining charge counter","healthRemaining");
        addHealthField(r,"Design capacity","healthDesign");
        addHealthField(r,"Capacity ratio","healthRatio");
        addHealthField(r,"Raw health source","healthRaw");
        addHealthField(r,"Source file","healthFile");
        addHealthField(r,"Full energy","healthFullEnergy");
        addHealthField(r,"Design energy","healthDesignEnergy");
        addHealthField(r,"Session-based capacity estimate (≥40% charge)","healthSessionEstimate");
        addHealthField(r,"Session-based health estimate","healthSessionHealth");
        LinearLayout row1=new LinearLayout(this);
        LinearLayout voltageCard=infoCard("VOLTAGE","--"); healthVoltage=(TextView)voltageCard.getTag();
        LinearLayout tempCard=infoCard("TEMPERATURE","--"); healthTemp=(TextView)tempCard.getTag();
        row1.addView(voltageCard,new LinearLayout.LayoutParams(0,-2,1)); LinearLayout.LayoutParams x=new LinearLayout.LayoutParams(0,-2,1); x.leftMargin=dp(8); row1.addView(tempCard,x);
        r.addView(row1,top(12));
        LinearLayout row2=new LinearLayout(this);
        LinearLayout currentCard=infoCard("CURRENT","--"); healthCurrent=(TextView)currentCard.getTag();
        LinearLayout sourceCard=infoCard("LIVE SOURCE","--"); healthSource=(TextView)sourceCard.getTag();
        row2.addView(currentCard,new LinearLayout.LayoutParams(0,-2,1)); x=new LinearLayout.LayoutParams(0,-2,1); x.leftMargin=dp(8); row2.addView(sourceCard,x);
        r.addView(row2,top(8));
        TextView note=text("BatteryPulse uses standard Android battery APIs and Linux power_supply data, with root access used to fill protected kernel values when available.",12); note.setTextColor(0xFF858D99); r.addView(note,top(12));
        return r;
    }
    private void addHealthField(LinearLayout parent,String label,String key){
        int bgColor=0xFF171A20;
        int strokeColor=0xFF303744;
        if("healthFullCharge".equals(key)) { bgColor=0xFF172334; strokeColor=0xFF63B3FF; }
        else if("healthRemaining".equals(key)) { bgColor=0xFF17271F; strokeColor=0xFF63E6A8; }
        else if("healthDesign".equals(key)) { bgColor=0xFF211E18; strokeColor=0xFFFFC86B; }
        else if("healthRatio".equals(key)) { bgColor=0xFF201B2B; strokeColor=0xFFB58CFF; }
        else if("healthRaw".equals(key)) { bgColor=0xFF261A20; strokeColor=0xFFFF7F9A; }
        else if("healthFile".equals(key)) { bgColor=0xFF18242A; strokeColor=0xFF69C7D8; }
        else if("healthFullEnergy".equals(key)||"healthDesignEnergy".equals(key)) { bgColor=0xFF182334; strokeColor=0xFF7CC7FF; }
        else if("healthSessionEstimate".equals(key)||"healthSessionHealth".equals(key)) { bgColor=0xFF17271F; strokeColor=0xFF63E6A8; }
        LinearLayout c=card(bgColor,strokeColor);
        TextView l=text(label,11); l.setTypeface(Typeface.DEFAULT,Typeface.BOLD); l.setTextColor(0xFF9AA0AA); c.addView(l,full());
        TextView v=text("Unavailable",16); v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); c.addView(v,top(4));
        if("healthFullCharge".equals(key)) healthFullCharge=v;
        else if("healthRemaining".equals(key)) healthRemaining=v;
        else if("healthDesign".equals(key)) healthDesign=v;
        else if("healthRatio".equals(key)) healthRatio=v;
        else if("healthRaw".equals(key)) healthRaw=v;
        else if("healthFile".equals(key)) healthFile=v;
        else if("healthFullEnergy".equals(key)) healthFullEnergy=v;
        else if("healthDesignEnergy".equals(key)) healthDesignEnergy=v;
        else if("healthSessionEstimate".equals(key)) healthSessionEstimate=v;
        else if("healthSessionHealth".equals(key)) healthSessionHealth=v;
        parent.addView(c,top(7));
    }
    private LinearLayout infoCard(String label,String value){
        LinearLayout c=card(0xFF171A20,0xFF303744); TextView l=text(label,10); l.setTypeface(Typeface.DEFAULT,Typeface.BOLD); l.setTextColor(0xFF8E96A3); c.addView(l,full());
        TextView v=text(value,17); v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); c.setTag(v); c.addView(v,top(5)); return c;
    }
    private void showTab(int index){
        monitorContainer.setVisibility(index==0?View.VISIBLE:View.GONE); historyView.setVisibility(index==1?View.VISIBLE:View.GONE); healthScroll.setVisibility(index==2?View.VISIBLE:View.GONE);
        selectTab(index); handler.removeCallbacks(historyUpdater);
        if(index==1){refreshHistory();handler.postDelayed(historyUpdater,10000);} else if(index==2) refreshHealth();
    }
    private void refreshHealth(){
        try{
            android.content.Intent b=registerReceiver(null,new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if(b!=null){
                int level=b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1), scale=b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100); int pct=(level>=0&&scale>0)?Math.round(level*100f/scale):-1;
                healthLevel.setText(pct>=0?pct+"%":"--"); int status=b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,-1);
                healthStatus.setText("Status: "+(status==android.os.BatteryManager.BATTERY_STATUS_CHARGING?"Charging":status==android.os.BatteryManager.BATTERY_STATUS_FULL?"Full":"Discharging / idle"));
                int mv=b.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE,0); healthVoltage.setText(mv>0?String.format(java.util.Locale.US,"%.3f V",mv/1000.0):"N/A");
                int temp=b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,0); healthTemp.setText(temp!=0?String.format(java.util.Locale.US,"%.1f °C",temp/10.0):"N/A");
            }
            BatteryReader.Reading rr=BatteryReader.read(this); healthCurrent.setText(rr.currentText()); healthSource.setText(rr.currentUa!=Long.MIN_VALUE?"Fuel gauge":"Android");
            BatteryHealthReader.Data d=BatteryHealthReader.read(this);
            healthFullCharge.setText(d.charge(d.fullChargeUaH)); healthRemaining.setText(d.charge(d.remainingChargeUaH)); healthDesign.setText(d.charge(d.designChargeUaH));
            healthRatio.setText(Double.isNaN(d.ratio)?"Unavailable":String.format(java.util.Locale.US,"%.1f %%",d.ratio));
            healthRaw.setText(d.rawHealth==null?"Unavailable":d.rawHealth); healthFile.setText(d.sourceFile);
            healthFullEnergy.setText(d.energy(d.fullEnergyUWh)); healthDesignEnergy.setText(d.energy(d.designEnergyUWh));
            SessionEstimate est=estimateFromSessions();
            healthSessionEstimate.setText(est.capacityMah>0?String.format(java.util.Locale.US,"%.0f mAh",est.capacityMah):"Unavailable");
            healthSessionHealth.setText(est.health>0?String.format(java.util.Locale.US,"%.1f %%  •  %d qualifying sessions",est.health,est.count):"Unavailable");
        }catch(Throwable ignored){
            healthLevel.setText("--"); healthStatus.setText("Status: unavailable"); healthVoltage.setText("N/A"); healthTemp.setText("N/A"); healthCurrent.setText("N/A"); healthSource.setText("Unavailable");
            healthFullCharge.setText("Unavailable"); healthRemaining.setText("Unavailable"); healthDesign.setText("Unavailable"); healthRatio.setText("Unavailable"); healthRaw.setText("Unavailable"); healthFile.setText("Unavailable"); healthFullEnergy.setText("Unavailable"); healthDesignEnergy.setText("Unavailable"); healthSessionEstimate.setText("Unavailable"); healthSessionHealth.setText("Unavailable");
        }
    }
    private SessionEstimate estimateFromSessions(){
        ArrayList<SessionStore.Record> records=SessionStore.getRecords(this);
        double weightedCapacity=0;
        double totalWeight=0;
        int count=0;
        for(SessionStore.Record rec:records){
            if(!rec.charging || rec.startPercent<0 || rec.endPercent<0) continue;
            int delta=rec.endPercent-rec.startPercent;
            if(delta<40 || rec.mah<=0) continue;
            double capacity=rec.mah*100.0/delta;
            if(capacity<1500 || capacity>6000) continue;
            double weight=Math.min(delta,100);
            weightedCapacity+=capacity*weight;
            totalWeight+=weight;
            count++;
        }
        if(totalWeight<=0) return new SessionEstimate(-1, -1, 0);
        double cap=weightedCapacity/totalWeight;
        return new SessionEstimate(cap,cap*100.0/4050.0,count);
    }

    private static final class SessionEstimate{
        final double capacityMah,health;
        final int count;
        SessionEstimate(double c,double h,int n){capacityMah=c;health=h;count=n;}
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
        if(healthView!=null&&healthView.getVisibility()==View.VISIBLE)refreshHealth();
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
            empty.setTextColor(0xFF777777);
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
            overall.setTextColor(0xFF777777);
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
            hint.setTextColor(0xFF888888);
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
            none.setTextColor(0xFF888888);
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
        l.setTextColor(0xFF777777);
        c.addView(l,full());
        TextView v=text(value,25);
        v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        c.setTag(v);
        c.addView(v,top(5));
        return c;
    }
    
    private LinearLayout toolCard(String title,String subtitle){
        LinearLayout c=card(0xFF211E18,0xFFFFC86B);
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
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1),0xFFDDE2E8);
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
    
    private void selectTab(int index){
        TextView[] ts={monitorTab,historyTab,healthTab};
        for(int i=0;i<ts.length;i++){boolean selected=i==index; ts[i].setTextColor(selected?0xFF111111:0xFF777777); ts[i].setBackground(round(selected?0xFFE7E7EA:0x00000000,12));}
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
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(0xFF202124);return v;}
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=full();p.topMargin=dp(n);return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
