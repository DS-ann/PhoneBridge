package com.dsann.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val label = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> "RINGING"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "OFFHOOK"
            TelephonyManager.EXTRA_STATE_IDLE -> "IDLE"
            else -> state
        }

        // Phase 1: state logging only. Phase 2 will publish this state
        // to connected PadPhone clients.
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
            .edit().putString("last_call_state", label).apply()
    }
}
