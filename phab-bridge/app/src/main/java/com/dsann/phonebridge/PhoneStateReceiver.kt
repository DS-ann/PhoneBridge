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

        context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
            .edit().putString("last_call_state", label).putString(BridgeService.KEY_STATUS, "Call state: $label").apply()

        context.startService(Intent(context, BridgeService::class.java).apply {
            action = BridgeService.ACTION_CALL_STATE
            putExtra(BridgeService.EXTRA_CALL_STATE, label)
        })
    }
}
