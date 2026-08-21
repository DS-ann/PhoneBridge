package com.dsann.phonebridge

import android.app.Activity
import android.os.Bundle
import android.telecom.Call
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal API-23 in-call UI for the PhoneBridge dialer. */
class InCallActivity : Activity() {
    private lateinit var stateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateText = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        val hangup = Button(this).apply {
            text = "END CALL"
            setOnClickListener {
                InCallController.hangup()
                finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(stateText, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(hangup, LinearLayout.LayoutParams(-1, -2))
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val call = InCallController.getCall()
        stateText.text = when (call?.state) {
            Call.STATE_RINGING -> "INCOMING CALL"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_ACTIVE -> "CALL ACTIVE"
            else -> "CALL"
        }
    }
}
