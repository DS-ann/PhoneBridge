package com.dsann.phonebridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            val audio = prefs.getString("audio_route", "Not available yet") ?: "Not available yet"
            val supported = prefs.getString("audio_supported", "") ?: ""
            status.text = "PhoneBridge\n\n$bridge\n\nCall: $call\nAudio route: $audio\nSupported: $supported"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "PhoneBridge\nStarting..."
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
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
            addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(stop, LinearLayout.LayoutParams(-1, -2))
        })

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 100)
        } else {
            startBridgeService()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startBridgeService()
        } else {
            status.text = "PhoneBridge\nPhone permission required"
        }
    }

    private fun startBridgeService() {
        startService(Intent(this, BridgeService::class.java))
    }
}
