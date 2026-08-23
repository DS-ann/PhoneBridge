package com.dsann.phonebridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors

/** TCP control connection used only to start/stop the two-way audio stream. */
class BridgeServer(private val context: android.content.Context) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())

    fun start() {
        executor.execute {
            try {
                serverSocket = ServerSocket(BridgeProtocol.PORT)
                context.getSharedPreferences(BridgeService.PREFS, 0).edit().putString(BridgeService.KEY_STATUS, "Listening on ${BridgeProtocol.PORT}").apply()
                while (!serverSocket!!.isClosed) handleClient(serverSocket!!.accept())
            } catch (_: Exception) {
                if (serverSocket?.isClosed != true) context.getSharedPreferences(BridgeService.PREFS, 0).edit().putString(BridgeService.KEY_STATUS, "Server stopped").apply()
            }
        }
    }

    fun stop() {
        AudioBridge.stop(context)
        try { serverSocket?.close() } catch (_: Exception) {}
        synchronized(clients) { clients.forEach { try { it.close() } catch (_: Exception) {} }; clients.clear() }
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        clients.add(socket)
        executor.execute {
            socket.use { s ->
                try {
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val writer = s.getOutputStream().bufferedWriter()
                    writer.write("READY\n"); writer.flush()
                    while (!s.isClosed) {
                        val command = reader.readLine()?.trim() ?: break
                        val result = when {
                            command.startsWith("AUDIO_START:") -> startAudio(command.substringAfter(':').trim(), s)
                            command == "AUDIO_STOP" -> { AudioBridge.stop(context); "OK:AUDIO_STOP" }
                            command == BridgeProtocol.PING -> "PONG"
                            else -> "ERROR:UNKNOWN_COMMAND"
                        }
                        writer.write(result + "\n"); writer.flush()
                    }
                } catch (_: Exception) {} finally { clients.remove(s) }
            }
        }
    }

    private fun startAudio(portText: String, socket: Socket): String {
        val port = portText.toInt().also { require(it in 1024..65535) { "invalid audio port" } }
        val address = socket.inetAddress ?: throw IllegalStateException("client address unavailable")
        AudioBridge.stop(context)
        check(AudioBridge.start(context, address, port)) { "audio start failed" }
        return "OK:AUDIO_START:$port"
    }
}
