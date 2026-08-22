package com.dsann.phonebridge.pad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class BridgeService : Service() {
    companion object {
        private const val CHANNEL_ID="phonebridge_bridge"; private const val NOTIFICATION_ID=45821
        private const val ACTION_CONNECT="com.dsann.phonebridge.CONNECT"; private const val ACTION_SEND="com.dsann.phonebridge.SEND"
        private const val ACTION_AUDIO_START="com.dsann.phonebridge.AUDIO_START"; private const val ACTION_AUDIO_STOP="com.dsann.phonebridge.AUDIO_STOP"
        private const val EXTRA_HOST="host"; private const val EXTRA_COMMAND="command"; private const val PREFS="bridge_service"; private const val PREF_HOST="host"
        const val ACTION_EVENT="com.dsann.phonebridge.BRIDGE_EVENT"; const val EXTRA_EVENT="event"
        fun start(c:Context,h:String)=startCompat(c,Intent(c,BridgeService::class.java).apply{action=ACTION_CONNECT;putExtra(EXTRA_HOST,h)})
        fun send(c:Context,x:String)=startCompat(c,Intent(c,BridgeService::class.java).apply{action=ACTION_SEND;putExtra(EXTRA_COMMAND,x)})
        fun startAudio(c:Context)=startCompat(c,Intent(c,BridgeService::class.java).setAction(ACTION_AUDIO_START))
        fun stopAudio(c:Context)=startCompat(c,Intent(c,BridgeService::class.java).setAction(ACTION_AUDIO_STOP))
        private fun startCompat(c:Context,i:Intent){if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i)else c.startService(i)}
    }
    private val io=Executors.newCachedThreadPool(); private val handler=Handler(Looper.getMainLooper())
    private var socket:Socket?=null; private var writer:PrintWriter?=null; private var phabHost="192.168.43.1"; private var reconnectScheduled=false; private var callAudioRequested=false
    override fun onCreate(){super.onCreate();phabHost=getSharedPreferences(PREFS,MODE_PRIVATE).getString(PREF_HOST,phabHost)?:phabHost;createNotificationChannel();startForeground(NOTIFICATION_ID,buildNotification("Bridge running"))}
    override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{when(i?.action){ACTION_CONNECT->connectTo(i.getStringExtra(EXTRA_HOST)?:phabHost);ACTION_SEND->sendCommand(i.getStringExtra(EXTRA_COMMAND)?:return START_STICKY);ACTION_AUDIO_START->{callAudioRequested=true;ensureWifiAudio()};ACTION_AUDIO_STOP->stopWifiAudio();null->connectTo(phabHost)};return START_STICKY}
    private fun connectTo(host:String){phabHost=host;getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(PREF_HOST,host).apply();if(socket?.isConnected==true&&!socket!!.isClosed){broadcast("CONNECTION:CONNECTED");return};closeSocket();broadcast("CONNECTION:CONNECTING");io.execute{try{val s=Socket();s.connect(InetSocketAddress(host,45821),5000);s.keepAlive=true;socket=s;writer=PrintWriter(s.getOutputStream(),true);broadcast("CONNECTION:CONNECTED");readLoop(s,BufferedReader(InputStreamReader(s.getInputStream())))}catch(e:Exception){broadcast("CONNECTION:FAILED:${e.javaClass.simpleName}");scheduleReconnect()}}}
    private fun readLoop(s:Socket,r:BufferedReader){try{while(!s.isClosed){val line=r.readLine()?:break;broadcast("PHAB:$line");when{line=="PONG"->broadcast("CONNECTION:PONG");line.startsWith("CALL_STATE:RINGING")||line.startsWith("CALL_STATE:DIALING")||line.startsWith("CALL_STATE:CONNECTING")||line.startsWith("CALL_STATE:OFFHOOK")||line.startsWith("CALL_STATE:ACTIVE")-> {callAudioRequested=true;broadcast("AUDIO:CALL_STATE_START:$line");ensureWifiAudio()};line.startsWith("CALL_STATE:IDLE")||line.startsWith("CALL_STATE:DISCONNECTED")->stopWifiAudio()}}}catch(_:Exception){}finally{if(socket===s){socket=null;writer=null;AudioClient.stop(this);broadcast("CONNECTION:DISCONNECTED");scheduleReconnect()}}}
    private fun ensureWifiAudio(){io.execute{if(!callAudioRequested||AudioClient.isRunning())return@execute;try{val p=AudioClient.start(this,InetAddress.getByName(phabHost));if(p<0)return@execute;broadcast("AUDIO:START_REQUEST:$p");sendCommand("AUDIO_START:$p");broadcast("AUDIO:STARTING:$p")}catch(e:Exception){broadcast("ERROR:AUDIO_START:${e.javaClass.simpleName}:${e.message?:""}");handler.postDelayed({if(callAudioRequested&&!AudioClient.isRunning())ensureWifiAudio()},750)}}}
    private fun sendCommand(x:String){io.execute{val w=writer;val s=socket;if(s==null||s.isClosed||w==null){broadcast("ERROR:NOT_CONNECTED");scheduleReconnect();return@execute};try{w.println(x);w.flush();if(w.checkError()){broadcast("ERROR:SEND_FAILED");scheduleReconnect()}}catch(e:Exception){broadcast("ERROR:SEND_FAILED:${e.javaClass.simpleName}");scheduleReconnect()}}}
    private fun startWifiAudio(){callAudioRequested=true;ensureWifiAudio()}
    private fun stopWifiAudio(){callAudioRequested=false;sendCommand("AUDIO_STOP");AudioClient.stop(this);broadcast("AUDIO:STOPPED")}
    private fun scheduleReconnect(){if(reconnectScheduled)return;reconnectScheduled=true;handler.postDelayed({reconnectScheduled=false;connectTo(phabHost)},2000)}
    private fun broadcast(x:String){sendBroadcast(Intent(ACTION_EVENT).apply{setPackage(packageName);putExtra(EXTRA_EVENT,x)})}
    private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"PhoneBridge",NotificationManager.IMPORTANCE_LOW))}
    private fun buildNotification(t:String):Notification{val p=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=23)PendingIntent.FLAG_IMMUTABLE else 0);return if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,CHANNEL_ID).setContentTitle("PhoneBridge").setContentText(t).setSmallIcon(android.R.drawable.sym_def_app_icon).setContentIntent(p).setOngoing(true).build()else{@Suppress("DEPRECATION") val b=Notification.Builder(this);b.setContentTitle("PhoneBridge").setContentText(t).setSmallIcon(android.R.drawable.sym_def_app_icon).setContentIntent(p).setOngoing(true).build()}}
    private fun closeSocket(){try{socket?.close()}catch(_:Exception){};socket=null;writer=null}
    override fun onBind(i:Intent?):IBinder?=null
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);callAudioRequested=false;AudioClient.stop(this);closeSocket();io.shutdownNow();super.onDestroy()}
}
