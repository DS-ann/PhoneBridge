package com.dsann.phonebridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors

class BridgeServer(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())

    fun start() {
        executor.execute {
            try {
                serverSocket = ServerSocket(BridgeProtocol.PORT)
                onStatus("Listening on port ${BridgeProtocol.PORT}")
                while (!serverSocket!!.isClosed) handleClient(serverSocket!!.accept())
            } catch (_: Exception) {
                if (serverSocket?.isClosed != true) onStatus("Server stopped")
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) { }
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (_: Exception) { } }
            clients.clear()
        }
        executor.shutdownNow()
    }

    fun broadcastCallState(state: String) {
        val message = "CALL_STATE:$state\n"
        onStatus("Call status: $state")
        synchronized(clients) {
            val dead = mutableListOf<Socket>()
            clients.forEach { socket ->
                try {
                    socket.getOutputStream().bufferedWriter().apply {
                        write(message)
                        flush()
                    }
                } catch (_: Exception) { dead.add(socket) }
            }
            dead.forEach { try { it.close() } catch (_: Exception) { }; clients.remove(it) }
        }
    }

    private fun handleClient(socket: Socket) {
        clients.add(socket)
        executor.execute {
            socket.use { s ->
                try {
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val writer = s.getOutputStream().bufferedWriter()
                    writer.write("READY\n")
                    writer.flush()

                    val lastState = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                        .getString("last_call_state", "IDLE") ?: "IDLE"
                    writer.write("CALL_STATE:$lastState\n")
                    writer.flush()

                    while (!s.isClosed) {
                        val line = reader.readLine() ?: break
                        writer.write(process(line.trim()) + "\n")
                        writer.flush()
                    }
                } catch (_: Exception) {
                } finally { clients.remove(s) }
            }
        }
    }

    private fun process(command: String): String {
        return try {
            when {
                command.startsWith("${BridgeProtocol.DIAL}:") -> {
                    val number = command.substringAfter(':').trim()
                    dial(number)
                    "OK:DIAL"
                }
                command == BridgeProtocol.PING -> "PONG"
                command == BridgeProtocol.ANSWER -> "UNSUPPORTED:ANSWER_PHASE1"
                command == BridgeProtocol.REJECT -> "UNSUPPORTED:REJECT_PHASE1"
                command == BridgeProtocol.HANGUP -> "UNSUPPORTED:HANGUP_PHASE1"
                else -> "ERROR:UNKNOWN_COMMAND"
            }
        } catch (e: Exception) {
            "ERROR:${e.javaClass.simpleName}:${e.message ?: "failed"}"
        }
    }

    private fun dial(number: String) {
        require(number.isNotEmpty()) { "empty number" }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
            .edit().putString("last_call_state", "DIALING").apply()
        onStatus("Call status: DIALING")
        broadcastCallState("DIALING")
        context.startActivity(intent)
    }
}
