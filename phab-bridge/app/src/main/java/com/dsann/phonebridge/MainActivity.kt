package com.dsann.phonebridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var number: EditText
    private lateinit var probeResult: TextView
    private lateinit var loopbackStatus: TextView
    private lateinit var callAudioDiagnostics: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
            val bridge = prefs.getString(BridgeService.KEY_STATUS, "Starting...") ?: "Starting..."
            val call = prefs.getString("last_call_state", "IDLE") ?: "IDLE"
            val audio = prefs.getString("audio_route", "Waiting for Telecom…") ?: "Waiting for Telecom…"
            val supported = prefs.getString("audio_supported", "") ?: ""
            val telecom = prefs.getString("telecom_info", "InCallService not bound") ?: "InCallService not bound"
            status.text = "PhoneBridge\n\nBridge: $bridge\nCall: $call\nTelecom: $telecom\nAudio route: $audio\nSupported: ${if (supported.isEmpty()) "—" else supported}"
            val report = prefs.getString("audio_probe", "") ?: ""
            if (report.isNotEmpty()) probeResult.text = report
            loopbackStatus.text = "Loopback: ${AudioProbe.loopbackStatus(this@MainActivity)}"
            callAudioDiagnostics.text = "Call audio diagnostics:\n${prefs.getString("audio_call_diagnostics", "Waiting for an active Telecom call…") ?: "Waiting for an active Telecom call…"}"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleDialIntent(intent)
        requestPermissionsIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun buildUi() {
        status = TextView(this).apply {
            text = "PhoneBridge\nStarting…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }
        number = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 22f
            setSingleLine(true)
            gravity = Gravity.CENTER
        }
        probeResult = TextView(this).apply {
            text = "No audio probe has been run."
            textSize = 12f
            setPadding(8, 8, 8, 8)
        }
        loopbackStatus = TextView(this).apply {
            text = "Loopback: NOT_STARTED"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }
        callAudioDiagnostics = TextView(this).apply {
            text = "Call audio diagnostics:\nWaiting for an active Telecom call…"
            textSize = 12f
            setPadding(8, 8, 8, 8)
        }

        val dialPad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val keys = arrayOf("1","2","3","4","5","6","7","8","9","*","0","#")
        for (row in 0 until 4) {
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER }
            for (col in 0 until 3) {
                val digit = keys[row * 3 + col]
                val b = Button(this).apply { text = digit; setOnClickListener { number.append(digit) } }
                line.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            }
            dialPad.addView(line, LinearLayout.LayoutParams(-1, -2))
        }

        val call = Button(this).apply { text = "CALL"; textSize = 18f }
        val probe = Button(this).apply {
            text = "Run audio probe"
            setOnClickListener {
                probeResult.text = "Running…\nDo this while a cellular call is active for the most useful result."
                AudioProbe.run(this@MainActivity)
            }
        }
        val startLoopback = Button(this).apply {
            text = "Start communication loopback"
            setOnClickListener {
                if (!AudioProbe.startLoopback(this@MainActivity)) {
                    loopbackStatus.text = "Loopback: ALREADY_RUNNING"
                }
            }
        }
        val stopLoopback = Button(this).apply {
            text = "Stop communication loopback"
            setOnClickListener {
                if (!AudioProbe.stopLoopback()) loopbackStatus.text = "Loopback: NOT_RUNNING"
            }
        }
        val clearProbe = Button(this).apply {
            text = "Clear probe result"
            setOnClickListener {
                getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit().remove("audio_probe").apply()
                probeResult.text = "No audio probe has been run."
            }
        }
        val defaultDialer = Button(this).apply { text = "Enable call controls"; setOnClickListener { requestDefaultDialer() } }
        val start = Button(this).apply { text = "Start bridge"; setOnClickListener { startBridgeService() } }
        val stop = Button(this).apply {
            text = "Stop bridge"
            setOnClickListener { stopService(Intent(this@MainActivity, BridgeService::class.java).apply { action = BridgeService.ACTION_STOP }) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 12, 12, 12)
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(number, LinearLayout.LayoutParams(-1, -2))
            addView(dialPad, LinearLayout.LayoutParams(-1, -2))
            addView(call, LinearLayout.LayoutParams(-1, -2))
            addView(callAudioDiagnostics, LinearLayout.LayoutParams(-1, -2))
            addView(probe, LinearLayout.LayoutParams(-1, -2))
            addView(startLoopback, LinearLayout.LayoutParams(-1, -2))
            addView(stopLoopback, LinearLayout.LayoutParams(-1, -2))
            addView(loopbackStatus, LinearLayout.LayoutParams(-1, -2))
            addView(clearProbe, LinearLayout.LayoutParams(-1, -2))
            addView(probeResult, LinearLayout.LayoutParams(-1, -2))
            addView(defaultDialer, LinearLayout.LayoutParams(-1, -2))
            addView(start, LinearLayout.LayoutParams(-1, -2))
            addView(stop, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(ScrollView(this).apply { addView(content) })

        call.setOnClickListener {
            val n = number.text.toString().trim()
            if (n.isNotEmpty()) startCellularCall(n) else status.text = "Enter a phone number"
        }
    }

    private fun handleDialIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_DIAL) return
        val uri = intent.data
        if (uri != null) number.setText(uri.schemeSpecificPart ?: "")
        number.requestFocus()
    }

    private fun startCellularCall(n: String) {
        try {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            if (android.os.Build.VERSION.SDK_INT >= 23 && packageName == telecom.defaultDialerPackage) {
                telecom.placeCall(Uri.fromParts("tel", n, null), null)
            } else startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null)))
        } catch (e: Exception) { status.text = "Call failed: ${e.javaClass.simpleName}" }
    }

    private fun requestPermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.RECORD_AUDIO), 100)
        } else startBridgeService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startBridgeService()
        else status.text = "PhoneBridge\nPhone permission required"
    }

    private fun requestDefaultDialer() {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        if (packageName == telecom.defaultDialerPackage) {
            status.text = "PhoneBridge\nAlready the default phone app"
            return
        }
        try {
            startActivity(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            })
        } catch (e: Exception) { status.text = "Could not open default phone-app selection: ${e.javaClass.simpleName}" }
    }

    private fun startBridgeService() { startService(Intent(this, BridgeService::class.java)) }
}
