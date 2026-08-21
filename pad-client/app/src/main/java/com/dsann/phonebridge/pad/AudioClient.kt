package com.dsann.phonebridge.pad

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
import java.util.concurrent.atomic.AtomicLong

object AudioClient {
    private const val SAMPLE_RATE = 8000
    private const val FRAME_SAMPLES = 160
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private val executor = Executors.newFixedThreadPool(2)
    private val running = AtomicBoolean(false)
    private val txFrames = AtomicLong(0)
    private val rxFrames = AtomicLong(0)
    private var socket: DatagramSocket? = null

    @Synchronized fun start(context: Context, phabAddress: InetAddress): Int {
        if (running.get()) return -1
        val localPort = findPort()
        val s = DatagramSocket(localPort)
        s.setReuseAddress(true)
        socket = s
        running.set(true)
        txFrames.set(0)
        rxFrames.set(0)

        executor.execute {
            var record: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) throw IllegalStateException("AUDIO_INPUT_UNAVAILABLE")
                record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4))
                if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AUDIO_INPUT_NOT_INITIALIZED")
                record.startRecording()
                val pcm = ShortArray(FRAME_SAMPLES)
                val bytes = ByteArray(FRAME_BYTES)
                while (running.get()) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n > 0) {
                        var p = 0
                        for (i in 0 until n) {
                            val v = pcm[i].toInt()
                            bytes[p++] = (v and 0xff).toByte()
                            bytes[p++] = ((v ushr 8) and 0xff).toByte()
                        }
                        s.send(DatagramPacket(bytes, n * 2, phabAddress, 45822))
                        txFrames.incrementAndGet()
                    }
                }
            } catch (e: Throwable) {
                context.getSharedPreferences("audio", Context.MODE_PRIVATE).edit().putString("status", "ERROR:TX:${e.javaClass.simpleName}:${e.message ?: ""}").apply()
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
                // Use normal media output on the Pad. STREAM_VOICE_CALL can be routed to
                // an earpiece/communication path and may be inaudible on some tablets.
                track = AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 8), AudioTrack.MODE_STREAM)
                if (track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AUDIO_OUTPUT_NOT_INITIALIZED")
                track.setStereoVolume(1.0f, 1.0f)
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
                        rxFrames.incrementAndGet()
                        if (rxFrames.get() % 25L == 0L) updateStatus(context, "RUNNING:$localPort TX:${txFrames.get()} RX:${rxFrames.get()}")
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) context.getSharedPreferences("audio", Context.MODE_PRIVATE).edit().putString("status", "ERROR:RX:${e.javaClass.simpleName}:${e.message ?: ""}").apply()
            } finally {
                try { track?.stop() } catch (_: Throwable) { }
                try { track?.release() } catch (_: Throwable) { }
            }
        }
        updateStatus(context, "RUNNING:$localPort TX:0 RX:0")
        return localPort
    }

    private fun updateStatus(context: Context, value: String) {
        context.getSharedPreferences("audio", Context.MODE_PRIVATE).edit().putString("status", value).apply()
    }

    private fun findPort(): Int {
        var p = 46000
        while (p < 46100) {
            try { DatagramSocket(p).use { return p } } catch (_: Exception) { p++ }
        }
        throw IllegalStateException("NO_UDP_PORT")
    }

    @Synchronized fun stop(context: Context) {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) { }
        socket = null
        updateStatus(context, "STOPPED TX:${txFrames.get()} RX:${rxFrames.get()}")
    }

    fun status(context: Context): String = context.getSharedPreferences("audio", Context.MODE_PRIVATE).getString("status", "STOPPED") ?: "STOPPED"
}
