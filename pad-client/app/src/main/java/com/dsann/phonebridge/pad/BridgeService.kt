package com.dsann.phonebridge.pad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class BridgeService : Service() {
    companion object {
        private const val CHANNEL_ID = "phonebridge_bridge"
        private const val NOTIFICATION_ID = 45821
        private const val ACTION_CONNECT = "com.dsann.phonebridge.CONNECT"
        private const val ACTION_SEND = "com.dsann.phonebridge.SEND"
        private const val ACTION_AUDIO_START = "com.dsann.phonebridge.AUDIO_START"
        private const val ACTION_AUDIO_STOP = "com.dsann.phonebridge.AUDIO_STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_COMMAND = "command"
        const val ACTION_EVENT = "com.dsann.phonebridge.BRIDGE_EVENT"
        const val EXTRA_EVENT = "event"

        fun start(context: Context, host: String) {
            val intent = Intent(context, BridgeService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST, host)
            }
            startCompat(context, intent)
        }

        fun send(context: Context, command: String) {
            val intent = Intent(context, BridgeService::class.java).apply {
                action = ACTION_SEND
                putExtra(EXTRA_COMMAND, command)
            }
            startCompat(context, intent)
        }

        fun startAudio(context: Context) {
            startCompat(context, Intent(context, BridgeService::class.java).setAction(ACTION_AUDIO_START))
        }

        fun stopAudio(context: Context) {
            startCompat(context, Intent(context, BridgeService::class.java).setAction(ACTION_AUDIO_STOP))
        }

        private fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private val io = Executors.newCachedThreadPool()
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var phabHost = "192.168.43.1"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Bridge running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connectTo(intent.getStringExtra(EXTRA_HOST) ?: phabHost)
            ACTION_SEND -> sendCommand(intent.getStringExtra(EXTRA_COMMAND) ?: return START_STICKY)
            ACTION_AUDIO_START -> startWifiAudio()
            ACTION_AUDIO_STOP -> stopWifiAudio()
        }
        return START_STICKY
    }

    private fun connectTo(host: String) {
        phabHost = host
        if (socket?.isConnected == true && socket?.isClosed == false) {
            broadcast("CONNECTION:CONNECTED")
            return
        }
        closeSocket()
        broadcast("CONNECTION:CONNECTING")
        io.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, 45821), 5000)
                s.keepAlive = true
                val w = PrintWriter(s.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                socket = s
                writer = w
                broadcast("CONNECTION:CONNECTED")
                readLoop(s, reader)
            } catch (e: Exception) {
                broadcast("CONNECTION:FAILED:${e.javaClass.simpleName}")
            }
        }
    }

    private fun readLoop(s: Socket, reader: BufferedReader) {
        try {
            while (!s.isClosed) {
                val line = reader.readLine() ?: break
                broadcast("PHAB:$line")
                when {
                    line == "PONG" -> broadcast("CONNECTION:PONG")
                    line.startsWith("CALL_STATE:RINGING") -> startWifiAudio()
                    line.startsWith("CALL_STATE:IDLE") -> stopWifiAudio()
                }
            }
        } catch (_: Exception) {
        } finally {
            if (socket === s) {
                socket = null
                writer = null
                AudioClient.stop(this)
                broadcast("CONNECTION:DISCONNECTED")
            }
        }
    }

    private fun sendCommand(command: String) {
        io.execute {
            val w = writer
            val s = socket
            if (s == null || s.isClosed || w == null) {
                broadcast("ERROR:NOT_CONNECTED")
                return@execute
            }
            try {
                w.println(command)
                w.flush()
                if (w.checkError()) broadcast("ERROR:SEND_FAILED")
            } catch (e: Exception) {
                broadcast("ERROR:SEND_FAILED:${e.javaClass.simpleName}")
            }
        }
    }

    private fun startWifiAudio() {
        io.execute {
            try {
                val address = InetAddress.getByName(phabHost)
                val port = AudioClient.start(this, address)
                if (port < 0) {
                    broadcast("AUDIO:ALREADY_RUNNING")
                    return@execute
                }
                sendCommand("AUDIO_START:$port")
                broadcast("AUDIO:STARTING:$port")
            } catch (e: SecurityException) {
                broadcast("ERROR:MIC_PERMISSION")
            } catch (e: Exception) {
                broadcast("ERROR:AUDIO_START:${e.javaClass.simpleName}:${e.message ?: ""}")
            }
        }
    }

    private fun stopWifiAudio() {
        sendCommand("AUDIO_STOP")
        AudioClient.stop(this)
        broadcast("AUDIO:STOPPED")
    }

    private fun broadcast(message: String) {
        sendBroadcast(Intent(ACTION_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_EVENT, message)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "PhoneBridge", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(this, 0, launch, flags)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID).setContentTitle("PhoneBridge").setContentText(text)
                .setSmallIcon(android.R.drawable.sym_def_app_icon).setContentIntent(pending).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("PhoneBridge").setContentText(text)
                .setSmallIcon(android.R.drawable.sym_def_app_icon).setContentIntent(pending).setOngoing(true).build()
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        writer = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AudioClient.stop(this)
        closeSocket()
        io.shutdownNow()
        super.onDestroy()
    }
}
