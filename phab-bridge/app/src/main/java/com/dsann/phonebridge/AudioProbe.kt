package com.dsann.phonebridge

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.util.concurrent.Executors

/**
 * Diagnostic only. It does not transmit or store recorded audio.
 * It checks what the app-level Android audio APIs expose while a call is active.
 */
object AudioProbe {
    private val executor = Executors.newSingleThreadExecutor()

    fun run(context: Context) {
        executor.execute {
            val prefs = context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val lines = ArrayList<String>()

            lines += "API:${Build.VERSION.SDK_INT}"
            lines += "MIC:${am.isMicrophoneMute.not()}"
            lines += "MODE:${am.mode}"
            lines += "SPEAKER:${am.isSpeakerphoneOn}"

            if (Build.VERSION.SDK_INT >= 23) {
                val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                lines += "INPUT_DEVICES:${inputs.joinToString(",") { deviceName(it) }}"
                lines += "OUTPUT_DEVICES:${outputs.joinToString(",") { deviceName(it) }}"
            }

            val sources = listOf(
                "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
                "MIC" to MediaRecorder.AudioSource.MIC,
                "VOICE_UPLINK" to MediaRecorder.AudioSource.VOICE_UPLINK,
                "VOICE_DOWNLINK" to MediaRecorder.AudioSource.VOICE_DOWNLINK,
                "VOICE_CALL" to MediaRecorder.AudioSource.VOICE_CALL,
                "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
                "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )

            for ((name, source) in sources) {
                val result = probeSource(source)
                lines += "SOURCE_$name:$result"
            }

            val report = lines.joinToString("\n")
            prefs.edit().putString("audio_probe", report).apply()
        }
    }

    private fun probeSource(source: Int): String {
        val min = try {
            AudioRecord.getMinBufferSize(
                8000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Throwable) {
            return "MIN_BUFFER_ERROR"
        }
        if (min <= 0) return "UNAVAILABLE(min=$min)"

        var record: AudioRecord? = null
        return try {
            record = AudioRecord(
                source,
                8000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                "NOT_INITIALIZED(state=${record.state})"
            } else {
                try {
                    record.startRecording()
                    val buffer = ShortArray(160)
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) "OPEN_READABLE" else "OPEN_READ_$read"
                } catch (e: Throwable) {
                    "OPEN_BUT_READ_ERROR:${e.javaClass.simpleName}"
                }
            }
        } catch (e: SecurityException) {
            "PERMISSION_DENIED"
        } catch (e: Throwable) {
            "OPEN_ERROR:${e.javaClass.simpleName}"
        } finally {
            try { record?.stop() } catch (_: Throwable) { }
            try { record?.release() } catch (_: Throwable) { }
        }
    }

    private fun deviceName(device: AudioDeviceInfo): String {
        return "type=${device.type}/id=${device.id}/name=${device.productName}"
    }
}
