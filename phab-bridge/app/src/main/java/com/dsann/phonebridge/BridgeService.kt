package com.dsann.phonebridge

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BridgeService : Service() {
    private var server: BridgeServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                server?.stop()
                server = null
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CALL_STATE -> {
                val state = intent.getStringExtra(EXTRA_CALL_STATE)
                if (!state.isNullOrEmpty()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_call_state", state)
                        .putString(KEY_STATUS, "Call state: $state")
                        .apply()
                    server?.broadcastCallState(state)
                }
                ensureServer()
                return START_STICKY
            }
            ACTION_TELECOM_INFO -> {
                val info = intent.getStringExtra(EXTRA_TELECOM_INFO)
                if (!info.isNullOrEmpty()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("telecom_info", info)
                        .putString(KEY_STATUS, info)
                        .apply()
                    server?.broadcastRaw(info)
                }
                ensureServer()
                return START_STICKY
            }
        }

        ensureServer()
        return START_STICKY
    }

    private fun ensureServer() {
        if (server == null) {
            server = BridgeServer(this) { message ->
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_STATUS, message).apply()
            }.also { it.start() }
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.dsann.phonebridge.STOP"
        const val ACTION_CALL_STATE = "com.dsann.phonebridge.CALL_STATE"
        const val EXTRA_CALL_STATE = "state"
        const val ACTION_TELECOM_INFO = "com.dsann.phonebridge.TELECOM_INFO"
        const val EXTRA_TELECOM_INFO = "info"
        const val PREFS = "bridge"
        const val KEY_STATUS = "status"
    }
}
