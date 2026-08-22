package com.dsann.phonebridge

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile

/** API-23 Telecom control/diagnostic service. Does not capture raw call PCM. */
class PhoneInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        InCallController.service = this
        publishTelecomInfo("BOUND")
        publishAudioState(callAudioState)
        publishAudioDiagnostics("onCreate")
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(changedCall: Call, state: Int) {
                InCallController.publishState(state)
                publishAudioDiagnostics("callState:${stateName(state)}")
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        InCallController.setCall(call)
        InCallController.publishState(call.state)
        publishAudioState(callAudioState)
        publishAudioDiagnostics("onCallAdded:${stateName(call.state)}")
        try { startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { callback -> try { call.unregisterCallback(callback) } catch (_: Throwable) {} }
        if (InCallController.getCall() === call) {
            InCallController.clearCall()
            InCallController.publishState(Call.STATE_DISCONNECTED)
        }
        publishAudioDiagnostics("onCallRemoved")
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) -> try { call.unregisterCallback(callback) } catch (_: Throwable) {} }
        callbacks.clear()
        InCallController.clearService(this)
        publishTelecomInfo("UNBOUND")
        super.onDestroy()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        publishAudioState(audioState)
        publishAudioDiagnostics("onCallAudioStateChanged")
    }

    private fun publishTelecomInfo(info: String) {
        getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit().putString("telecom_info", info).apply()
        try {
            startService(Intent(this, BridgeService::class.java).apply {
                action = BridgeService.ACTION_TELECOM_INFO
                putExtra(BridgeService.EXTRA_TELECOM_INFO, "TELECOM:$info")
            })
        } catch (_: Exception) { }
    }

    private fun publishAudioState(state: CallAudioState?) {
        if (state == null) return
        val route = routeName(state.route)
        val supported = supportedRoutes(state.supportedRouteMask)
        val bluetoothDevices = try {
            state.supportedBluetoothDevices.joinToString(",") { it.name ?: it.address }
        } catch (_: Throwable) { "UNAVAILABLE" }
        getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit()
            .putString("audio_route", route)
            .putString("audio_supported", supported)
            .putString("audio_bluetooth_devices", bluetoothDevices)
            .putBoolean("audio_muted", state.isMuted).apply()
        try {
            startService(Intent(this, BridgeService::class.java).apply {
                action = BridgeService.ACTION_TELECOM_INFO
                putExtra(BridgeService.EXTRA_TELECOM_INFO, "AUDIO_ROUTE:$route;SUPPORTED:$supported;BT_DEVICES:$bluetoothDevices;MUTED:${state.isMuted}")
            })
        } catch (_: Exception) { }
    }

    /** API-23 state-only diagnostic; no raw call audio is captured or transmitted. */
    private fun publishAudioDiagnostics(event: String) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val telecom = callAudioState
            val modeName = when (am.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "MODE_${am.mode}"
            }
            val telecomRoute = if (telecom == null) "NONE" else routeName(telecom.route)
            val supported = if (telecom == null) "NONE" else supportedRoutes(telecom.supportedRouteMask)
            val callState = InCallController.getCall()?.state ?: Call.STATE_NEW
            val report = "event=$event;callState=${stateName(callState)}($callState);telecomRoute=$telecomRoute;telecomSupported=$supported;telecomMuted=${telecom?.isMuted};mode=$modeName(${am.mode});speaker=${am.isSpeakerphoneOn};micMute=${am.isMicrophoneMute};wired=${am.isWiredHeadsetOn};musicActive=${am.isMusicActive}"
            getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit().putString("audio_call_diagnostics", report).apply()
            startService(Intent(this, BridgeService::class.java).apply {
                action = BridgeService.ACTION_TELECOM_INFO
                putExtra(BridgeService.EXTRA_TELECOM_INFO, "AUDIO_DIAG:$report")
            })
        } catch (e: Throwable) {
            getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit().putString("audio_call_diagnostics", "ERROR:${e.javaClass.simpleName}:${e.message ?: ""}").apply()
        }
    }

    fun requestRoute(route: Int): String {
        return try {
            val state = callAudioState
            if (state == null) return "ERROR:NO_AUDIO_STATE"
            if ((state.supportedRouteMask and route) == 0) return "ERROR:UNSUPPORTED_ROUTE:${routeName(route)}"
            setAudioRoute(route)
            publishAudioDiagnostics("routeRequest:${routeName(route)}")
            "OK:ROUTE:${routeName(route)}"
        } catch (e: Throwable) { "ERROR:ROUTE:${e.javaClass.simpleName}:${e.message ?: ""}" }
    }

    private fun routeName(route: Int): String = when (route) {
        CallAudioState.ROUTE_EARPIECE -> "EARPIECE"
        CallAudioState.ROUTE_SPEAKER -> "SPEAKER"
        CallAudioState.ROUTE_BLUETOOTH -> "BLUETOOTH"
        CallAudioState.ROUTE_WIRED_HEADSET -> "WIRED_HEADSET"
        CallAudioState.ROUTE_WIRED_OR_EARPIECE -> "WIRED_OR_EARPIECE"
        else -> "UNKNOWN($route)"
    }

    private fun stateName(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_CONNECTING -> "CONNECTING"
        else -> "STATE_$state"
    }

    private fun supportedRoutes(mask: Int): String {
        val names = ArrayList<String>()
        if ((mask and CallAudioState.ROUTE_EARPIECE) != 0) names.add("EARPIECE")
        if ((mask and CallAudioState.ROUTE_SPEAKER) != 0) names.add("SPEAKER")
        if ((mask and CallAudioState.ROUTE_BLUETOOTH) != 0) names.add("BLUETOOTH")
        if ((mask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) names.add("WIRED_HEADSET")
        return if (names.isEmpty()) "NONE" else names.joinToString(",")
    }
}

object InCallController {
    @Volatile var service: PhoneInCallService? = null
    @Volatile private var currentCall: Call? = null

    @Synchronized fun setCall(call: Call) { currentCall = call }
    @Synchronized fun clearCall() { currentCall = null }
    fun getCall(): Call? = currentCall
    fun clearService(instance: PhoneInCallService) { if (service === instance) service = null; clearCall() }

    fun answer(): String { val call = currentCall ?: return "ERROR:NO_CALL"; return try { call.answer(VideoProfile.STATE_AUDIO_ONLY); "OK:ANSWER" } catch (e: Throwable) { "ERROR:ANSWER:${e.javaClass.simpleName}" } }
    fun reject(): String { val call = currentCall ?: return "ERROR:NO_CALL"; return try { call.reject(false, null); "OK:REJECT" } catch (e: Throwable) { "ERROR:REJECT:${e.javaClass.simpleName}" } }
    fun hangup(): String { val call = currentCall ?: return "ERROR:NO_CALL"; return try { call.disconnect(); "OK:HANGUP" } catch (e: Throwable) { "ERROR:HANGUP:${e.javaClass.simpleName}" } }

    fun routeEarpiece(): String = service?.requestRoute(CallAudioState.ROUTE_EARPIECE) ?: "ERROR:NO_SERVICE"
    fun routeSpeaker(): String = service?.requestRoute(CallAudioState.ROUTE_SPEAKER) ?: "ERROR:NO_SERVICE"
    fun routeBluetooth(): String = service?.requestRoute(CallAudioState.ROUTE_BLUETOOTH) ?: "ERROR:NO_SERVICE"

    fun publishState(state: Int) {
        val label = when (state) {
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "OFFHOOK"
            Call.STATE_DISCONNECTED -> "IDLE"
            Call.STATE_CONNECTING -> "CONNECTING"
            else -> "STATE_$state"
        }
        val svc = service ?: return
        try { svc.startService(Intent(svc, BridgeService::class.java).apply { action = BridgeService.ACTION_CALL_STATE; putExtra(BridgeService.EXTRA_CALL_STATE, label) }) } catch (_: Exception) { }
    }
}
