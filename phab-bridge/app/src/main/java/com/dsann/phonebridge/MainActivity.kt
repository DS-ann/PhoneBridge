package com.dsann.phonebridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
            val bridge = prefs.getString(BridgeService.KEY_STATUS, "Starting...") ?: "Starting..."
            val call = prefs.getString("last_call_state", "IDLE") ?: "IDLE"
            val audio = prefs.getString("audio_route", "Waiting for Telecom…") ?: "Waiting for Telecom…"
            val supported = prefs.getString("audio_supported", "") ?: ""
            val telecom = prefs.getString("telecom_info", "InCallService not bound") ?: "InCallService not bound"
            status.text = "PhoneBridge\n\nBridge: $bridge\n\nCall: $call\nTelecom: $telecom\nAudio route: $audio\nSupported: ${if (supported.isEmpty()) "—" else supported}"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun buildUi() {
        status = TextView(this).apply {
            text = "PhoneBridge\nStarting…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }

        val defaultDialer = Button(this).apply {
            text = "Enable call controls"
            setOnClickListener { requestDefaultDialer() }
        }

        val start = Button(this).apply {
            text = "Start bridge"
            setOnClickListener { startBridgeService() }
        }

        val stop = Button(this).apply {
            text = "Stop bridge"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_STOP
                })
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(defaultDialer, LinearLayout.LayoutParams(-1, -2))
            addView(start, LinearLayout.LayoutParams(-1, -2))
            addView(stop, LinearLayout.LayoutParams(-1, -2))
        })
    }

    private fun requestPermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE),
                100
            )
        } else {
            startBridgeService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startBridgeService()
        } else {
            status.text = "PhoneBridge\nPhone permission required"
        }
    }

    private fun requestDefaultDialer() {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        if (packageName == telecom.defaultDialerPackage) {
            status.text = "PhoneBridge\nAlready the default phone app"
            return
        }
        try {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            status.text = "PhoneBridge\nCould not open default phone-app settings\n${e.javaClass.simpleName}"
        }
    }

    private fun startBridgeService() {
        startService(Intent(this, BridgeService::class.java))
    }
}
