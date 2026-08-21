package com.dsann.phonebridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class BridgeServer(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        executor.execute {
            try {
                serverSocket = ServerSocket(BridgeProtocol.PORT)
                onStatus("Listening on port ${BridgeProtocol.PORT}")
                while (!serverSocket!!.isClosed) {
                    handleClient(serverSocket!!.accept())
                }
            } catch (_: Exception) {
                onStatus("Server stopped")
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) { }
    }

    private fun handleClient(socket: Socket) {
        executor.execute {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = s.getOutputStream().bufferedWriter()
                writer.write("READY\n")
                writer.flush()

                while (!s.isClosed) {
                    val line = reader.readLine() ?: break
                    val response = process(line.trim())
                    writer.write(response + "\n")
                    writer.flush()
                }
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
        context.startActivity(intent)
        onStatus("Dialing $number")
    }
}
