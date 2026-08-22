package com.dsann.phonebridge.pad

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var connectionStatus: TextView
    private lateinit var callStatus: TextView
    private lateinit var resultStatus: TextView
    private lateinit var audioStatus: TextView
    private lateinit var number: EditText
    private lateinit var timer: TextView
    private var callStartedAt = 0L
    private var receiverRegistered = false
    private val phabHost = "192.168.43.1"

    private val timerTask = object : Runnable {
        override fun run() {
            val started = callStartedAt
            timer.text = if (started == 0L) "00:00" else formatDuration(SystemClock.elapsedRealtime() - started)
            audioStatus.text = "Audio: ${AudioClient.status(this@MainActivity)}"
            ui.postDelayed(this, 500)
        }
    }

    private val bridgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent?.getStringExtra(BridgeService.EXTRA_EVENT) ?: return
            when {
                event == "CONNECTION:CONNECTING" -> connectionStatus.text = "● Connecting…"
                event == "CONNECTION:CONNECTED" -> connectionStatus.text = "● Connected • Phab"
                event == "CONNECTION:PONG" -> connectionStatus.text = "● Connected • PONG"
                event == "CONNECTION:DISCONNECTED" -> connectionStatus.text = "● Disconnected"
                event.startsWith("CONNECTION:FAILED:") -> connectionStatus.text = "● Connection failed: ${event.substringAfterLast(':')}"
                event.startsWith("PHAB:CALL_STATE:") -> setCallStatus(event.substringAfter("PHAB:"))
                event.startsWith("PHAB:AUDIO_ROUTE:") -> resultStatus.text = "Audio: ${event.substringAfter("PHAB:").substringAfter(':')}"
                event.startsWith("PHAB:OK:AUDIO_START") -> resultStatus.text = "✓ Wi-Fi audio connected"
                event.startsWith("PHAB:OK:") -> resultStatus.text = "✓ ${event.substringAfter("PHAB:OK:")}"
                event.startsWith("PHAB:ERROR:") -> resultStatus.text = "✕ ${event.substringAfter("PHAB:ERROR:")}"
                event.startsWith("AUDIO:STARTING:") -> resultStatus.text = "Starting Wi-Fi audio • UDP ${event.substringAfterLast(':')}"
                event == "AUDIO:ALREADY_RUNNING" -> resultStatus.text = "✓ Wi-Fi audio already running"
                event == "AUDIO:STOPPED" -> resultStatus.text = "Wi-Fi audio stopped"
                event.startsWith("ERROR:") -> resultStatus.text = "✕ ${event.substringAfter(':')}"
                else -> resultStatus.text = event
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestAudioPermissionIfNeeded()
        registerBridgeReceiver()
        ui.post(timerTask)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.rgb(248, 249, 252))
        }
        root.addView(TextView(this).apply {
            text = "PhoneBridge"
            textSize = 28f
            setTextColor(Color.rgb(30, 35, 45))
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Cellular phone • Wi-Fi bridge"
            textSize = 14f
            gravity = Gravity.CENTER
        })
        connectionStatus = TextView(this).apply { text = "● Disconnected"; textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 18, 0, 8) }
        callStatus = TextView(this).apply { text = "IDLE"; textSize = 26f; gravity = Gravity.CENTER; setPadding(0, 18, 0, 4) }
        timer = TextView(this).apply { text = "00:00"; textSize = 16f; gravity = Gravity.CENTER }
        resultStatus = TextView(this).apply { text = ""; textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 4, 0, 8) }
        audioStatus = TextView(this).apply { text = "Audio: STOPPED"; textSize = 15f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }
        number = EditText(this).apply { hint = "Phone number"; inputType = android.text.InputType.TYPE_CLASS_PHONE; textSize = 22f; setSingleLine(true); gravity = Gravity.CENTER }

        root.addView(connectionStatus); root.addView(callStatus); root.addView(timer); root.addView(resultStatus); root.addView(audioStatus)
        root.addView(number, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 20, 0, 8) })

        val call = Button(this).apply { text = "☎  CALL"; textSize = 18f }
        root.addView(call)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val answer = Button(this).apply { text = "ANSWER" }
        val reject = Button(this).apply { text = "REJECT" }
        val hangup = Button(this).apply { text = "END CALL" }
        row.addView(answer, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(reject, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(hangup, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(row)

        val connect = Button(this).apply { text = "Connect to Phab" }
        val ping = Button(this).apply { text = "PING (test)" }
        val startAudio = Button(this).apply { text = "Start Wi-Fi audio" }
        val stopAudio = Button(this).apply { text = "Stop Wi-Fi audio" }
        root.addView(connect); root.addView(ping); root.addView(startAudio); root.addView(stopAudio)
        setContentView(root)

        connect.setOnClickListener { connectTo(phabHost) }
        ping.setOnClickListener { send("PING") }
        call.setOnClickListener {
            val n = number.text.toString().trim()
            if (n.isEmpty()) setResult("Enter a phone number") else { setCallStatus("DIALING"); send("DIAL:$n") }
        }
        answer.setOnClickListener { send("ANSWER") }
        reject.setOnClickListener { send("REJECT") }
        hangup.setOnClickListener { send("HANGUP") }
        startAudio.setOnClickListener { startWifiAudio() }
        stopAudio.setOnClickListener { BridgeService.stopAudio(this) }
    }

    private fun registerBridgeReceiver() {
        val filter = IntentFilter(BridgeService.ACTION_EVENT)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(bridgeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(bridgeReceiver, filter)
        receiverRegistered = true
    }

    private fun requestAudioPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 300)
        }
    }

    private fun startWifiAudio() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setResult("Microphone permission required")
            requestAudioPermissionIfNeeded()
            return
        }
        BridgeService.start(this, phabHost)
        BridgeService.startAudio(this)
        setResult("Starting Wi-Fi audio")
    }

    private fun connectTo(host: String) {
        BridgeService.start(this, host)
    }

    private fun setResult(value: String) = runOnUiThread { resultStatus.text = value }
    private fun setCallStatus(value: String) = runOnUiThread {
        callStatus.text = value
        when {
            value == "OFFHOOK" || value == "ACTIVE" -> if (callStartedAt == 0L) callStartedAt = SystemClock.elapsedRealtime()
            value == "IDLE" -> callStartedAt = 0L
        }
    }

    private fun send(command: String) = BridgeService.send(this, command)

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(bridgeReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        ui.removeCallbacks(timerTask)
        // The bridge is deliberately NOT stopped here. BridgeService owns the
        // socket/audio lifecycle so rotation and leaving the Activity do not disconnect it.
        super.onDestroy()
    }
}
