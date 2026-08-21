package com.dsann.phonebridge

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile

/** API-23 Telecom control/diagnostic service. Does not capture raw call PCM. */
class PhoneInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            InCallController.publishState(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        InCallController.service = this
        publishTelecomInfo("BOUND")
        publishAudioState(callAudioState)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        InCallController.setCall(call)
        call.registerCallback(callback)
        InCallController.publishState(call.state)
        publishAudioState(callAudioState)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (InCallController.getCall() === call) {
            InCallController.clearCall()
            InCallController.publishState(Call.STATE_DISCONNECTED)
        }
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        InCallController.clearService(this)
        publishTelecomInfo("UNBOUND")
        super.onDestroy()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        publishAudioState(audioState)
    }

    private fun publishTelecomInfo(info: String) {
        getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit()
            .putString("telecom_info", info)
            .apply()
        startService(Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_TELECOM_INFO
            putExtra(BridgeService.EXTRA_TELECOM_INFO, "TELECOM:$info")
        })
    }

    private fun publishAudioState(state: CallAudioState?) {
        if (state == null) return
        val route = when (state.route) {
            CallAudioState.ROUTE_EARPIECE -> "EARPIECE"
            CallAudioState.ROUTE_SPEAKER -> "SPEAKER"
            CallAudioState.ROUTE_BLUETOOTH -> "BLUETOOTH"
            CallAudioState.ROUTE_WIRED_HEADSET -> "WIRED_HEADSET"
            CallAudioState.ROUTE_WIRED_OR_EARPIECE -> "WIRED_OR_EARPIECE"
            else -> "UNKNOWN(${state.route})"
        }
        val supported = supportedRoutes(state.supportedRouteMask)
        getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit()
            .putString("audio_route", route)
            .putString("audio_supported", supported)
            .putBoolean("audio_muted", state.isMuted)
            .apply()
        val info = "AUDIO_ROUTE:$route;SUPPORTED:$supported;MUTED:${state.isMuted}"
        startService(Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_TELECOM_INFO
            putExtra(BridgeService.EXTRA_TELECOM_INFO, info)
        })
    }

    private fun supportedRoutes(mask: Int): String {
        val names = ArrayList<String>()
        if ((mask and CallAudioState.ROUTE_EARPIECE) != 0) names.add("EARPIECE")
        if ((mask and CallAudioState.ROUTE_SPEAKER) != 0) names.add("SPEAKER")
        if ((mask and CallAudioState.ROUTE_BLUETOOTH) != 0) names.add("BLUETOOTH")
        if ((mask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) names.add("WIRED_HEADSET")
        return names.joinToString(",")
    }
}

object InCallController {
    @Volatile var service: PhoneInCallService? = null
    @Volatile private var currentCall: Call? = null

    @Synchronized fun setCall(call: Call) {
        currentCall?.let { old -> try { old.unregisterCallback(null) } catch (_: Throwable) {} }
        currentCall = call
    }

    @Synchronized fun clearCall() { currentCall = null }
    fun getCall(): Call? = currentCall

    fun clearService(instance: PhoneInCallService) {
        if (service === instance) service = null
        clearCall()
    }

    fun answer(): String {
        val call = currentCall ?: return "ERROR:NO_CALL"
        return try { call.answer(VideoProfile.STATE_AUDIO_ONLY); "OK:ANSWER" }
        catch (e: Throwable) { "ERROR:ANSWER:${e.javaClass.simpleName}" }
    }

    fun reject(): String {
        val call = currentCall ?: return "ERROR:NO_CALL"
        return try { call.reject(false, null); "OK:REJECT" }
        catch (e: Throwable) { "ERROR:REJECT:${e.javaClass.simpleName}" }
    }

    fun hangup(): String {
        val call = currentCall ?: return "ERROR:NO_CALL"
        return try { call.disconnect(); "OK:HANGUP" }
        catch (e: Throwable) { "ERROR:HANGUP:${e.javaClass.simpleName}" }
    }

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
        svc.startService(Intent(svc, BridgeService::class.java).apply {
            action = BridgeService.ACTION_CALL_STATE
            putExtra(BridgeService.EXTRA_CALL_STATE, label)
        })
    }
}
