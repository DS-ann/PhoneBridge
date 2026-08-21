package com.dsann.phonebridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

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
                status.text = "PhoneBridge\nStopped"
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
        val saved = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
            .getString(BridgeService.KEY_STATUS, null)
        if (saved != null) status.text = "PhoneBridge\n$saved"
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
        status.text = "PhoneBridge\nStarting server on port ${BridgeProtocol.PORT}..."
    }
}
