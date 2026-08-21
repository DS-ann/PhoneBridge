package com.dsann.phonebridge.pad

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class MainActivity : AppCompatActivity() {
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

    private fun connectTo(host: String) {
        if (host.isEmpty()) {
            status.text = "Enter the Phab IP address"
            return
        }
        status.text = "Connecting..."
        Thread {
            try {
                val s = Socket(host, 45821)
                socket = s
                writer = PrintWriter(s.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                runOnUiThread { status.text = "Connected to $host" }
                while (true) {
                    val line = reader.readLine() ?: break
                    runOnUiThread { status.text = line }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Connection failed: ${e.message}" }
            }
        }.start()
    }

    private fun send(command: String) {
        try {
            writer?.println(command) ?: run { status.text = "Not connected" }
        } catch (e: Exception) {
            status.text = "Send failed: ${e.message}"
        }
    }

    override fun onDestroy() {
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
