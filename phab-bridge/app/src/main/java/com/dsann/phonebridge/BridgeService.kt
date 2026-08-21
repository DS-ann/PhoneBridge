package com.dsann.phonebridge

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BridgeService : Service() {
    private var server: BridgeServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            server?.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        if (server == null) {
            server = BridgeServer(this) { message ->
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_STATUS, message)
                    .apply()
            }.also { it.start() }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.dsann.phonebridge.STOP"
        const val PREFS = "bridge"
        const val KEY_STATUS = "status"
    }
}
