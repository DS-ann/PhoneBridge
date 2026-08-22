package com.dsann.phonebridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnostic only. Never stores or transmits captured audio. */
object AudioProbe {
    private val executor = Executors.newSingleThreadExecutor()
    private var loopbackTask: Future<*>? = null
    private val loopbackRunning = AtomicBoolean(false)

    fun run(context: Context) {
        executor.execute {
            val prefs = context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val lines = ArrayList<String>()
            lines += "API:${Build.VERSION.SDK_INT}"
            lines += "MIC:${!am.isMicrophoneMute}"
            lines += "MODE:${am.mode}"
            lines += "SPEAKER:${am.isSpeakerphoneOn}"
            lines += "CALL_SOURCE_PROBE:BEGIN"
            val sources = listOf(
                "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
                "MIC" to MediaRecorder.AudioSource.MIC,
                "VOICE_UPLINK" to MediaRecorder.AudioSource.VOICE_UPLINK,
                "VOICE_DOWNLINK" to MediaRecorder.AudioSource.VOICE_DOWNLINK,
                "VOICE_CALL" to MediaRecorder.AudioSource.VOICE_CALL,
                "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
                "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
            for ((name, source) in sources) lines += "SOURCE_$name:${probeSource(source)}"
            lines += "CALL_SOURCE_PROBE:END"

            lines += "AUDIO_PORT_PATCH_PROBE:BEGIN"
            lines += "ACTIVE_MODE:${am.mode}"
            lines += "MODE_IN_CALL:${AudioManager.MODE_IN_CALL}"
            try {
                val ports = ArrayList<Any>()
                val method = findAudioManagerMethod("listAudioPorts")
                lines += "LIST_AUDIO_PORTS_API:${if (method != null) "AVAILABLE" else "UNAVAILABLE"}"
                if (method != null) {
                    lines += "LIST_AUDIO_PORTS_RC:${invokeListMethod(method, am, ports)}"
                    lines += "AUDIO_PORT_COUNT:${ports.size}"
                    for (i in ports.indices) appendPortSummary(lines, i, ports[i])
                }
            } catch (e: Throwable) {
                lines += "AUDIO_PORT_ENUM_ERROR:${e.javaClass.simpleName}:${e.message ?: ""}"
            }
            try {
                val patches = ArrayList<Any>()
                val method = findAudioManagerMethod("listAudioPatches")
                lines += "LIST_AUDIO_PATCHES_API:${if (method != null) "AVAILABLE" else "UNAVAILABLE"}"
                if (method != null) {
                    lines += "LIST_AUDIO_PATCHES_RC:${invokeListMethod(method, am, patches)}"
                    lines += "AUDIO_PATCH_COUNT:${patches.size}"
                    for (i in patches.indices) appendPatchSummary(lines, i, patches[i])
                }
            } catch (e: Throwable) {
                lines += "AUDIO_PATCH_ENUM_ERROR:${e.javaClass.simpleName}:${e.message ?: ""}"
            }
            lines += "PROBE_CHANGED_ROUTING:NO"
            lines += "AUDIO_PORT_PATCH_PROBE:END"
            prefs.edit().putString("audio_probe", lines.joinToString("\n")).apply()
        }
    }

    private fun findAudioManagerMethod(name: String): Method? = try {
        AudioManager::class.java.getDeclaredMethod(name, ArrayList::class.java).apply { isAccessible = true }
    } catch (_: Throwable) {
        try { AudioManager::class.java.getMethod(name, ArrayList::class.java).apply { isAccessible = true } }
        catch (_: Throwable) { null }
    }

    private fun invokeListMethod(method: Method, manager: AudioManager, list: ArrayList<Any>): Int = try {
        (method.invoke(manager, list) as Number).toInt()
    } catch (_: IllegalArgumentException) {
        (method.invoke(null, list) as Number).toInt()
    }

    private fun appendPortSummary(lines: ArrayList<String>, index: Int, port: Any?) {
        if (port == null) { lines += "PORT[$index]=NULL"; return }
        lines += "PORT[$index]_CLASS:${port.javaClass.name}"
        appendObjectMethod(lines, "PORT[$index]_ID", port, "id")
        appendObjectMethod(lines, "PORT[$index]_NAME", port, "name")
        appendObjectMethod(lines, "PORT[$index]_ROLE", port, "role")
        appendObjectMethod(lines, "PORT[$index]_TYPE", port, "type")
        appendObjectMethod(lines, "PORT[$index]_HANDLE", port, "handle")
        appendObjectMethod(lines, "PORT[$index]_ADDRESS", port, "address")
        appendObjectMethod(lines, "PORT[$index]_FORMATS", port, "formats")
        appendObjectMethod(lines, "PORT[$index]_SAMPLING_RATES", port, "samplingRates")
        appendObjectMethod(lines, "PORT[$index]_CHANNEL_MASKS", port, "channelMasks")
        appendObjectMethod(lines, "PORT[$index]_CHANNEL_INDEX_MASKS", port, "channelIndexMasks")
    }

    private fun appendPatchSummary(lines: ArrayList<String>, index: Int, patch: Any?) {
        if (patch == null) { lines += "PATCH[$index]=NULL"; return }
        lines += "PATCH[$index]_CLASS:${patch.javaClass.name}"
        appendObjectMethod(lines, "PATCH[$index]_HANDLE", patch, "handle")
        appendObjectMethod(lines, "PATCH[$index]_SOURCES", patch, "sources")
        appendObjectMethod(lines, "PATCH[$index]_SINKS", patch, "sinks")
        appendPatchConfigDetails(lines, index, "SOURCES", patch, "sources")
        appendPatchConfigDetails(lines, index, "SINKS", patch, "sinks")
    }

    private fun appendPatchConfigDetails(lines: ArrayList<String>, patchIndex: Int, side: String, patch: Any, accessor: String) {
        try {
            val method = patch.javaClass.methods.firstOrNull { it.name == accessor && it.parameterTypes.isEmpty() } ?: return
            val value = method.invoke(patch) ?: return
            val configs = value as? Array<*> ?: return
            lines += "PATCH[$patchIndex]_${side}_COUNT:${configs.size}"
            for (i in configs.indices) {
                val cfg = configs[i] ?: continue
                val prefix = "PATCH[$patchIndex]_${side}[$i]"
                lines += "${prefix}_CLASS:${cfg.javaClass.name}"
                appendObjectMethod(lines, "${prefix}_PORT", cfg, "port")
                appendObjectMethod(lines, "${prefix}_SAMPLING_RATE", cfg, "samplingRate")
                appendObjectMethod(lines, "${prefix}_CHANNEL_MASK", cfg, "channelMask")
                appendObjectMethod(lines, "${prefix}_CHANNEL_INDEX_MASK", cfg, "channelIndexMask")
                appendObjectMethod(lines, "${prefix}_FORMAT", cfg, "format")
                appendNestedPortIdentity(lines, prefix, cfg)
            }
        } catch (_: Throwable) { }
    }

    private fun appendNestedPortIdentity(lines: ArrayList<String>, prefix: String, config: Any) {
        try {
            val method = config.javaClass.methods.firstOrNull { it.name == "port" && it.parameterTypes.isEmpty() } ?: return
            val port = method.invoke(config) ?: return
            appendObjectMethod(lines, "${prefix}_PORT_ID", port, "id")
            appendObjectMethod(lines, "${prefix}_PORT_ROLE", port, "role")
            appendObjectMethod(lines, "${prefix}_PORT_TYPE", port, "type")
            appendObjectMethod(lines, "${prefix}_PORT_NAME", port, "name")
            appendObjectMethod(lines, "${prefix}_PORT_ADDRESS", port, "address")
        } catch (_: Throwable) { }
    }

    private fun appendObjectMethod(lines: ArrayList<String>, key: String, obj: Any, methodName: String) {
        try {
            val method = obj.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() } ?: return
            val value = method.invoke(obj) ?: return
            lines += "$key:$value"
        } catch (_: Throwable) { }
    }

    @Synchronized fun startLoopback(context: Context): Boolean {
        if (loopbackRunning.get()) return false
        loopbackRunning.set(true)
        loopbackTask = executor.submit {
            var recorder: AudioRecord? = null
            var track: AudioTrack? = null
            val prefs = context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val oldMode = am.mode
            val oldSpeaker = am.isSpeakerphoneOn
            try {
                val sampleRate = 8000
                val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minIn <= 0 || minOut <= 0) throw IllegalStateException("AUDIO_BUFFER_UNAVAILABLE")
                val bufferSize = maxOf(minIn, minOut, 2048)
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("VOICE_COMMUNICATION_RECORD_UNAVAILABLE")
                track = if (Build.VERSION.SDK_INT >= 21) AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build() else null
                if (track == null || track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("VOICE_COMMUNICATION_OUTPUT_UNAVAILABLE")
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                recorder.startRecording(); track.play()
                prefs.edit().putString("audio_loopback", "RUNNING").apply()
                val pcm = ShortArray(1024)
                while (loopbackRunning.get()) { val n = recorder.read(pcm, 0, pcm.size); if (n > 0) track.write(pcm, 0, n) }
                prefs.edit().putString("audio_loopback", "STOPPED").apply()
            } catch (e: Throwable) { prefs.edit().putString("audio_loopback", "ERROR:${e.javaClass.simpleName}:${e.message ?: ""}").apply() }
            finally {
                try { recorder?.stop() } catch (_: Throwable) {}; try { recorder?.release() } catch (_: Throwable) {}
                try { track?.stop() } catch (_: Throwable) {}; try { track?.release() } catch (_: Throwable) {}
                try { am.mode = oldMode } catch (_: Throwable) {}; try { am.isSpeakerphoneOn = oldSpeaker } catch (_: Throwable) {}
                loopbackRunning.set(false)
            }
        }
        return true
    }

    @Synchronized fun stopLoopback(): Boolean { if (!loopbackRunning.get()) return false; loopbackRunning.set(false); return true }
    fun loopbackStatus(context: Context): String = context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).getString("audio_loopback", "NOT_STARTED") ?: "NOT_STARTED"

    private fun probeSource(source: Int): String {
        val min = try { AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) } catch (_: Throwable) { return "MIN_BUFFER_ERROR" }
        if (min <= 0) return "UNAVAILABLE(min=$min)"
        var record: AudioRecord? = null
        return try {
            record = AudioRecord(source, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            if (record.state != AudioRecord.STATE_INITIALIZED) "NOT_INITIALIZED(state=${record.state})" else {
                try { record.startRecording(); val buffer = ShortArray(160); val read = record.read(buffer, 0, buffer.size); if (read > 0) "OPEN_READABLE" else "OPEN_READ_$read" }
                catch (e: SecurityException) { "READ_PERMISSION_DENIED" } catch (e: Throwable) { "OPEN_BUT_READ_ERROR:${e.javaClass.simpleName}" }
            }
        } catch (e: SecurityException) { "PERMISSION_DENIED" } catch (e: Throwable) { "OPEN_ERROR:${e.javaClass.simpleName}" }
        finally { try { record?.stop() } catch (_: Throwable) {}; try { record?.release() } catch (_: Throwable) {} }
    }
}
