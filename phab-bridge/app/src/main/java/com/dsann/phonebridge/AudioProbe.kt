package com.dsann.phonebridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnostic only. Loopback uses the app's VOICE_COMMUNICATION path and never transmits or stores audio. */
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
            prefs.edit().putString("audio_probe", lines.joinToString("\n")).apply()
        }
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
                track = if (Build.VERSION.SDK_INT >= 21) {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else null
                if (track == null || track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("VOICE_COMMUNICATION_OUTPUT_UNAVAILABLE")
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                recorder.startRecording()
                track.play()
                prefs.edit().putString("audio_loopback", "RUNNING").apply()
                val pcm = ShortArray(1024)
                while (loopbackRunning.get()) {
                    val n = recorder.read(pcm, 0, pcm.size)
                    if (n > 0) track.write(pcm, 0, n)
                }
                prefs.edit().putString("audio_loopback", "STOPPED").apply()
            } catch (e: Throwable) {
                prefs.edit().putString("audio_loopback", "ERROR:${e.javaClass.simpleName}:${e.message ?: ""}").apply()
            } finally {
                try { recorder?.stop() } catch (_: Throwable) {}
                try { recorder?.release() } catch (_: Throwable) {}
                try { track?.stop() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
                try { am.mode = oldMode } catch (_: Throwable) {}
                try { am.isSpeakerphoneOn = oldSpeaker } catch (_: Throwable) {}
                loopbackRunning.set(false)
            }
        }
        return true
    }

    @Synchronized fun stopLoopback(): Boolean {
        if (!loopbackRunning.get()) return false
        loopbackRunning.set(false)
        return true
    }

    fun loopbackStatus(context: Context): String = context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).getString("audio_loopback", "NOT_STARTED") ?: "NOT_STARTED"

    private fun probeSource(source: Int): String {
        val min = try { AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) } catch (_: Throwable) { return "MIN_BUFFER_ERROR" }
        if (min <= 0) return "UNAVAILABLE(min=$min)"
        var record: AudioRecord? = null
        return try {
            record = AudioRecord(source, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            if (record.state != AudioRecord.STATE_INITIALIZED) "NOT_INITIALIZED(state=${record.state})"
            else {
                try { record.startRecording(); val buffer = ShortArray(160); val read = record.read(buffer, 0, buffer.size); if (read > 0) "OPEN_READABLE" else "OPEN_READ_$read" }
                catch (e: Throwable) { "OPEN_BUT_READ_ERROR:${e.javaClass.simpleName}" }
            }
        } catch (e: SecurityException) { "PERMISSION_DENIED" }
        catch (e: Throwable) { "OPEN_ERROR:${e.javaClass.simpleName}" }
        finally { try { record?.stop() } catch (_: Throwable) {}; try { record?.release() } catch (_: Throwable) {} }
    }
}
