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
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
            status.text = "PhoneBridge\n\n${prefs.getString(BridgeService.KEY_STATUS, "Starting…")}\nWi-Fi audio: ${prefs.getString("audio_wifi", "STOPPED") ?: "STOPPED"}"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestAudioPermission()
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun buildUi() {
        status = TextView(this).apply {
            text = "PhoneBridge\nStarting…"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(16, 24, 16, 24)
        }
        val start = Button(this).apply {
            text = "Start Wi-Fi audio"
            setOnClickListener { startBridgeService() }
        }
        val stop = Button(this).apply {
            text = "Stop Wi-Fi audio"
            setOnClickListener { stopService(Intent(this@MainActivity, BridgeService::class.java).apply { action = BridgeService.ACTION_STOP }) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(start, LinearLayout.LayoutParams(-1, -2))
            addView(stop, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun requestAudioPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        } else startBridgeService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startBridgeService()
        else status.text = "PhoneBridge\nMicrophone permission required"
    }

    private fun startBridgeService() { startService(Intent(this, BridgeService::class.java)) }
}
