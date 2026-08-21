package com.dsann.phonebridge.pad

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newCachedThreadPool()
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val ip = EditText(this).apply {
            hint = "Phab IP address"
            setSingleLine(true)
        }
        val connect = Button(this).apply { text = "Connect" }
        val ping = Button(this).apply { text = "PING" }
        status = TextView(this).apply {
            text = "Disconnected"
            textSize = 18f
        }

        root.addView(ip)
        root.addView(connect)
        root.addView(ping)
        root.addView(status)
        setContentView(root)

        connect.setOnClickListener { connectTo(ip.text.toString().trim()) }
        ping.setOnClickListener { send("PING") }
    }

    private fun setStatus(value: String) {
        runOnUiThread { status.text = value }
    }

    private fun connectTo(host: String) {
        if (host.isEmpty()) {
            setStatus("Enter the Phab IP address")
            return
        }

        closeConnection()
        setStatus("Connecting to $host:45821...")

        io.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, 45821), 5000)
                s.keepAlive = true
                val w = PrintWriter(s.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))

                socket = s
                writer = w
                setStatus("Connected to $host")

                // Reading is deliberately on its own task so it never blocks send().
                io.execute {
                    try {
                        while (!s.isClosed) {
                            val line = reader.readLine() ?: break
                            setStatus(line)
                        }
                    } catch (e: Exception) {
                        if (!s.isClosed) {
                            setStatus("Read failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                        }
                    } finally {
                        if (socket === s) {
                            socket = null
                            writer = null
                            setStatus("Disconnected from $host")
                        }
                    }
                }
            } catch (e: Exception) {
                socket = null
                writer = null
                setStatus("Connection failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }
    }

    private fun send(command: String) {
        io.execute {
            val s = socket
            val w = writer
            if (s == null || s.isClosed || w == null) {
                setStatus("Not connected")
                return@execute
            }

            try {
                w.println(command)
                w.flush()
                if (w.checkError()) {
                    setStatus("Send failed: socket write error")
                } else {
                    setStatus("Sent: $command")
                }
            } catch (e: Exception) {
                setStatus("Send failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }
    }

    private fun closeConnection() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
    }

    override fun onDestroy() {
        closeConnection()
        io.shutdownNow()
        super.onDestroy()
    }
}
