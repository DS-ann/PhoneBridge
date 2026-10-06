package com.dsann.batterymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private final Runnable updater=new Runnable(){public void run(){updateValues();handler.postDelayed(this,1000);}};
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER);
        Button m=new Button(this),h=new Button(this);m.setText("Monitor");h.setText("History");
        tabs.addView(m,new LinearLayout.LayoutParams(0,-2,1));tabs.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(tabs);
        monitorView=buildMonitor();historyView=buildHistory();
        root.addView(monitorView,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(historyView,new LinearLayout.LayoutParams(-1,0,1));historyView.setVisibility(View.GONE);
        m.setOnClickListener(v->{monitorView.setVisibility(View.VISIBLE);historyView.setVisibility(View.GONE);});
        h.setOnClickListener(v->{monitorView.setVisibility(View.GONE);historyView.setVisibility(View.VISIBLE);refreshHistory();});
        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }
    private LinearLayout buildMonitor(){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setGravity(Gravity.CENTER_HORIZONTAL);
        int p=dp(24);r.setPadding(p,dp(20),p,p);
        r.addView(text("Battery Monitor",24),full());
        voltage=text("Voltage: --",30);r.addView(voltage,top(24));
        current=text("Current: --",30);r.addView(current,top(12));

        LinearLayout settingsRow=new LinearLayout(this);settingsRow.setOrientation(LinearLayout.HORIZONTAL);settingsRow.setGravity(Gravity.CENTER_VERTICAL);
        notificationSwitch=new Switch(this);notificationSwitch.setText("Record in background");notificationSwitch.setTextSize(16);
        settingsRow.addView(notificationSwitch,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout rateBox=new LinearLayout(this);rateBox.setOrientation(LinearLayout.VERTICAL);
        samplingLabel=text("Sampling: 15 s",14);rateBox.addView(samplingLabel,full());
        samplingBar=new SeekBar(this);samplingBar.setMax(INTERVALS_MS.length-1);rateBox.addView(samplingBar,new LinearLayout.LayoutParams(-1,-2));
        settingsRow.addView(rateBox,new LinearLayout.LayoutParams(0,-2,1));r.addView(settingsRow,top(28));

        android.content.SharedPreferences prefs=getSharedPreferences(PREFS,0);
        int index=indexForInterval(prefs.getLong(KEY_INTERVAL_MS,15000L));
        samplingBar.setProgress(index);samplingLabel.setText("Sampling: "+INTERVAL_LABELS[index]);
        notificationSwitch.setChecked(prefs.getBoolean("notification_enabled",false));samplingBar.setEnabled(notificationSwitch.isChecked());
        samplingBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){samplingLabel.setText("Sampling: "+INTERVAL_LABELS[progress]);if(fromUser)getSharedPreferences(PREFS,0).edit().putLong(KEY_INTERVAL_MS,INTERVALS_MS[progress]).apply();}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });
        r.addView(text("Background recording uses the foreground notification service.",13),top(8));

        LinearLayout dozeBox=new LinearLayout(this);
        dozeBox.setOrientation(LinearLayout.VERTICAL);
        dozeBox.addView(text("Force Doze (root)",18),full());
        LinearLayout dozeRow=new LinearLayout(this);
        dozeRow.setGravity(Gravity.CENTER_VERTICAL);
        Button enableDoze=new Button(this);enableDoze.setText("Enable");
        Button disableDoze=new Button(this);disableDoze.setText("Disable");
        dozeRow.addView(enableDoze,new LinearLayout.LayoutParams(0,-2,1));
        dozeRow.addView(disableDoze,new LinearLayout.LayoutParams(0,-2,1));
        dozeBox.addView(dozeRow,top(4));
        TextView dozeStatus=text("Forces Android into Doze for testing. Disable restores normal idle behavior.",12);
        dozeBox.addView(dozeStatus,top(2));
        r.addView(dozeBox,top(20));

        enableDoze.setOnClickListener(v->{
            DozeController.Result result=DozeController.forceDoze();
            dozeStatus.setText(result.message);
        });
        disableDoze.setOnClickListener(v->{
            DozeController.Result result=DozeController.disableForceDoze();
            dozeStatus.setText(result.message);
        });

        LinearLayout percentBox=new LinearLayout(this);
        percentBox.setOrientation(LinearLayout.VERTICAL);
        TextView percentTitle=text("Battery % changer (root)",18);
        percentBox.addView(percentTitle,full());
        LinearLayout percentRow=new LinearLayout(this);
        percentRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText percentInput=new EditText(this);
        percentInput.setHint("0–100");
        percentInput.setSingleLine(true);
        percentInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        percentRow.addView(percentInput,new LinearLayout.LayoutParams(0,-2,1));
        Button setPercent=new Button(this);
        setPercent.setText("Set");
        percentRow.addView(setPercent,new LinearLayout.LayoutParams(-2,-2));
        Button resetPercent=new Button(this);
        resetPercent.setText("Reset");
        percentRow.addView(resetPercent,new LinearLayout.LayoutParams(-2,-2));
        percentBox.addView(percentRow,top(4));
        TextView percentStatus=text("Uses Android's battery test override; Reset restores the real battery value.",12);
        percentBox.addView(percentStatus,top(2));
        r.addView(percentBox,top(24));

        setPercent.setOnClickListener(v->{
            String value=percentInput.getText().toString().trim();
            if(value.isEmpty()){percentStatus.setText("Enter a value from 0 to 100.");return;}
            try{
                BatteryPercentOverride.Result result=BatteryPercentOverride.set(Integer.parseInt(value));
                percentStatus.setText(result.message);
            }catch(NumberFormatException e){
                percentStatus.setText("Enter a value from 0 to 100.");
            }
        });
        resetPercent.setOnClickListener(v->{
            BatteryPercentOverride.Result result=BatteryPercentOverride.reset();
            percentStatus.setText(result.message);
        });

        notificationSwitch.setOnCheckedChangeListener((b,on)->{
            getSharedPreferences(PREFS,0).edit().putBoolean("notification_enabled",on).apply();samplingBar.setEnabled(on);
            if(on){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);startNotificationService();}
            else stopService(new Intent(this,BatteryNotificationService.class));
        });return r;
    }
    private int indexForInterval(long ms){int best=0;long diff=Math.abs(INTERVALS_MS[0]-ms);for(int i=1;i<INTERVALS_MS.length;i++){long d=Math.abs(INTERVALS_MS[i]-ms);if(d<diff){diff=d;best=i;}}return best;}
    private LinearLayout buildHistory(){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(16),dp(12),dp(16),dp(12));
        r.addView(text("Session History",24),full());r.addView(text("Charge/discharge mAh calculated from measured current over time.",13),top(6));
        Button clear=new Button(this);clear.setText("Clear history");clear.setOnClickListener(v->{SessionStore.clear(this);refreshHistory();});r.addView(clear,top(8));
        ScrollView s=new ScrollView(this);historyList=new LinearLayout(this);historyList.setOrientation(LinearLayout.VERTICAL);s.addView(historyList);r.addView(s,new LinearLayout.LayoutParams(-1,0,1));return r;
    }
    @Override protected void onResume(){super.onResume();updateValues();handler.removeCallbacks(updater);handler.post(updater);if(historyView!=null&&historyView.getVisibility()==View.VISIBLE)refreshHistory();}
    @Override protected void onPause(){handler.removeCallbacks(updater);super.onPause();}
    private void updateValues(){BatteryReader.Reading r=BatteryReader.read(this);SessionStore.sample(this,r);voltage.setText("Voltage: "+r.voltageText());current.setText("Current: "+r.currentText());}
    private void refreshHistory(){
        historyList.removeAllViews();ArrayList<SessionStore.Record> rs=SessionStore.getRecords(this);
        if(rs.isEmpty()){historyList.addView(text("No completed sessions yet.",16));return;}
        for(SessionStore.Record rec:rs){
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(10),dp(12),dp(10));
            GradientDrawable bg=new GradientDrawable();bg.setColor(0x00000000);bg.setStroke(dp(1),0xFF888888);bg.setCornerRadius(dp(8));card.setBackground(bg);
            TextView title=text((rec.charging?"CHARGED":"USED")+"  "+String.format(java.util.Locale.US,"%.1f mAh",rec.mah),18);
            TextView details=text(rec.date()+"  •  "+rec.duration(),14);
            card.addView(title,full());card.addView(details,top(4));
            String currentRange=Double.isNaN(rec.minCurrent)?"Current: --":"Current: "+String.format(java.util.Locale.US,"min %+.1f mA  •  max %+.1f mA",rec.minCurrent,rec.maxCurrent);
            String percentRange=(rec.startPercent>=0&&rec.endPercent>=0)?"Battery: "+rec.startPercent+"% → "+rec.endPercent+"%  ("+String.format(java.util.Locale.US,"%+d%%",rec.endPercent-rec.startPercent)+")":"Battery: --";
            card.addView(text(currentRange,14),top(4));
            card.addView(text(percentRange,14),top(2));
            LinearLayout.LayoutParams cp=full();cp.topMargin=dp(8);historyList.addView(card,cp);
        }
    }
    private void startNotificationService(){Intent i=new Intent(this,BatteryNotificationService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);return v;}
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=full();p.topMargin=dp(n);return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}