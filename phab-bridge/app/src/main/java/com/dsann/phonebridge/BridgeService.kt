package com.dsann.phonebridge

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BridgeService : Service() {
    private var server: BridgeServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            server?.stop()
            server = null
            stopSelf()
            return START_NOT_STICKY
        }
        ensureServer()
        return START_STICKY
    }

    private fun ensureServer() {
        if (server == null) server = BridgeServer(this).also { it.start() }
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
