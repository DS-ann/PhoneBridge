package com.dsann.phonebridge

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaRecorder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Pure two-way Wi-Fi PCM audio bridge. No telephony/call handling. */
object AudioBridge {
    private const val SAMPLE_RATE = 8000
    private const val FRAME_SAMPLES = 160
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private const val AUDIO_PORT = 45822
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2)
    private var socket: DatagramSocket? = null

    @Synchronized
    fun start(context: Context, address: InetAddress, remotePort: Int, localPort: Int = AUDIO_PORT): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try { socket = DatagramSocket(localPort).apply { reuseAddress = true } }
        catch (e: Throwable) {
            running.set(false)
            setStatus(context, "ERROR:UDP:${e.message ?: "socket failed"}")
            return false
        }
        val s = socket!!
        setStatus(context, "RUNNING")

        // Phab microphone -> Wi-Fi -> Pad speaker.
        executor.execute {
            var record: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(min > 0) { "AUDIO_INPUT_UNAVAILABLE" }
                record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4))
                check(record.state == AudioRecord.STATE_INITIALIZED) { "AUDIO_INPUT_NOT_INITIALIZED" }
                record.startRecording()
                val pcm = ShortArray(FRAME_SAMPLES)
                val bytes = ByteArray(FRAME_BYTES)
                while (running.get()) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n > 0 && running.get()) {
                        var p = 0
                        for (i in 0 until n) { val v = pcm[i].toInt(); bytes[p++] = (v and 0xff).toByte(); bytes[p++] = ((v ushr 8) and 0xff).toByte() }
                        s.send(DatagramPacket(bytes, n * 2, address, remotePort))
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) setStatus(context, "ERROR:MIC:${e.javaClass.simpleName}:${e.message ?: ""}")
            } finally { try { record?.stop() } catch (_: Throwable) {}; try { record?.release() } catch (_: Throwable) {} }
        }

        // Pad microphone -> Wi-Fi -> Phab speaker.
        executor.execute {
            var track: AudioTrack? = null
            try {
                val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(min > 0) { "AUDIO_OUTPUT_UNAVAILABLE" }
                track = AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 8), AudioTrack.MODE_STREAM)
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AUDIO_OUTPUT_NOT_INITIALIZED" }
                track.play()
                val packet = ByteArray(2048)
                val datagram = DatagramPacket(packet, packet.size)
                while (running.get()) {
                    datagram.length = packet.size
                    s.receive(datagram)
                    if (datagram.length > 0 && running.get()) {
                        val samples = datagram.length / 2
                        val pcm = ShortArray(samples)
                        for (i in 0 until samples) { val lo = packet[i * 2].toInt() and 0xff; val hi = packet[i * 2 + 1].toInt(); pcm[i] = ((hi shl 8) or lo).toShort() }
                        track.write(pcm, 0, pcm.size)
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) setStatus(context, "ERROR:SPEAKER:${e.javaClass.simpleName}:${e.message ?: ""}")
            } finally { try { track?.stop() } catch (_: Throwable) {}; try { track?.release() } catch (_: Throwable) {} }
        }
        return true
    }

    private fun setStatus(context: Context, value: String) = context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).edit().putString("audio_wifi", value).apply()

    @Synchronized fun stop(context: Context) {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        setStatus(context, "STOPPED")
    }

    fun isRunning(): Boolean = running.get()
}
