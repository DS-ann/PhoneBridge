package com.dsann.phonebridge

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-way diagnostic audio transport. Uses UDP and 8 kHz mono PCM16.
 * It is intentionally independent of the cellular Telecom call path.
 */
object AudioBridge {
    private const val SAMPLE_RATE = 8000
    private const val FRAME_SAMPLES = 160 // 20 ms
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(3)
    private var socket: DatagramSocket? = null

    @Synchronized fun start(context: Context, address: InetAddress, remotePort: Int, localPort: Int): Boolean {
        if (running.get()) return false
        running.set(true)
        executor.execute {
            var record: AudioRecord? = null
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) throw IllegalStateException("AUDIO_INPUT_UNAVAILABLE")
                record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4))
                if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("VOICE_COMMUNICATION_INPUT_UNAVAILABLE")
                synchronized(this) { socket = DatagramSocket(localPort) }
                val s = socket ?: throw IllegalStateException("UDP_SOCKET_FAILED")
                context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).edit().putString("audio_wifi", "RUNNING").apply()
                record.startRecording()
                val buffer = ByteArray(FRAME_BYTES)
                val shorts = ShortArray(FRAME_SAMPLES)
                while (running.get()) {
                    val n = record.read(shorts, 0, FRAME_SAMPLES)
                    if (n > 0) {
                        var p = 0
                        for (i in 0 until n) {
                            val v = shorts[i].toInt()
                            buffer[p++] = (v and 0xff).toByte()
                            buffer[p++] = ((v ushr 8) and 0xff).toByte()
                        }
                        s.send(DatagramPacket(buffer, n * 2, address, remotePort))
                    }
                }
            } catch (e: Throwable) {
                context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).edit().putString("audio_wifi", "ERROR:${e.javaClass.simpleName}:${e.message ?: ""}").apply()
            } finally {
                try { record?.stop() } catch (_: Throwable) { }
                try { record?.release() } catch (_: Throwable) { }
            }
        }
        executor.execute {
            var track: AudioTrack? = null
            try {
                val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) throw IllegalStateException("AUDIO_OUTPUT_UNAVAILABLE")
                track = AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4), AudioTrack.MODE_STREAM)
                if (track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("VOICE_OUTPUT_UNAVAILABLE")
                val s = socket ?: waitForSocket()
                track.play()
                val packet = ByteArray(2048)
                val datagram = DatagramPacket(packet, packet.size)
                while (running.get()) {
                    datagram.length = packet.size
                    s.receive(datagram)
                    if (datagram.length > 0) {
                        val samples = datagram.length / 2
                        val pcm = ShortArray(samples)
                        for (i in 0 until samples) {
                            val lo = packet[i * 2].toInt() and 0xff
                            val hi = packet[i * 2 + 1].toInt()
                            pcm[i] = ((hi shl 8) or lo).toShort()
                        }
                        track.write(pcm, 0, pcm.size)
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).edit().putString("audio_wifi", "ERROR:${e.javaClass.simpleName}:${e.message ?: ""}").apply()
            } finally {
                try { track?.stop() } catch (_: Throwable) { }
                try { track?.release() } catch (_: Throwable) { }
            }
        }
        return true
    }

    private fun waitForSocket(): DatagramSocket {
        repeat(100) {
            socket?.let { return it }
            Thread.sleep(10)
        }
        throw IllegalStateException("UDP_SOCKET_NOT_READY")
    }

    @Synchronized fun stop(context: Context) {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) { }
        socket = null
        context.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).edit().putString("audio_wifi", "STOPPED").apply()
    }

    fun isRunning(): Boolean = running.get()
}
