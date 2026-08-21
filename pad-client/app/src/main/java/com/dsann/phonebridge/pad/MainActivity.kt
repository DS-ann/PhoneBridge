package com.dsann.phonebridge.pad

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
    private lateinit var connectionStatus: TextView
    private lateinit var callStatus: TextView
    private lateinit var number: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply { text = "PhoneBridge"; textSize = 24f; gravity = Gravity.CENTER }
        number = EditText(this).apply {
            hint = "Number to call"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            textSize = 22f
        }
        val ip = EditText(this).apply { hint = "Phab IP address"; setSingleLine(true); setText("192.168.43.1") }
        val connect = Button(this).apply { text = "Connect" }
        val call = Button(this).apply { text = "CALL" }
        val ping = Button(this).apply { text = "PING" }
        connectionStatus = TextView(this).apply { text = "Connection: Disconnected"; textSize = 17f }
        callStatus = TextView(this).apply { text = "Call status: IDLE"; textSize = 20f; setPadding(0, 24, 0, 24) }

        root.addView(title)
        root.addView(number)
        root.addView(ip)
        root.addView(connect)
        root.addView(call)
        root.addView(ping)
        root.addView(connectionStatus)
        root.addView(callStatus)
        setContentView(root)

        connect.setOnClickListener { connectTo(ip.text.toString().trim()) }
        ping.setOnClickListener { send("PING") }
        call.setOnClickListener {
            val n = number.text.toString().trim()
            if (n.isEmpty()) setConnectionStatus("Enter a number")
            else {
                setCallStatus("DIALING")
                send("DIAL:$n")
            }
        }
    }

    private fun setConnectionStatus(value: String) = runOnUiThread { connectionStatus.text = "Connection: $value" }
    private fun setCallStatus(value: String) = runOnUiThread { callStatus.text = "Call status: $value" }

    private fun connectTo(host: String) {
        if (host.isEmpty()) { setConnectionStatus("Enter the Phab IP address"); return }
        closeConnection()
        setConnectionStatus("Connecting to $host:45821...")
        io.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, 45821), 5000)
                s.keepAlive = true
                val w = PrintWriter(s.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                socket = s
                writer = w
                setConnectionStatus("Connected to $host")
                io.execute {
                    try {
                        while (!s.isClosed) {
                            val line = reader.readLine() ?: break
                            when {
                                line.startsWith("CALL_STATE:") -> setCallStatus(line.substringAfter(':'))
                                else -> setConnectionStatus(line)
                            }
                        }
                    } catch (e: Exception) {
                        if (!s.isClosed) setConnectionStatus("Read failed: ${e.javaClass.simpleName}")
                    } finally {
                        if (socket === s) {
                            socket = null
                            writer = null
                            setConnectionStatus("Disconnected from $host")
                        }
                    }
                }
            } catch (e: Exception) {
                socket = null
                writer = null
                setConnectionStatus("Connection failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }
    }

    private fun send(command: String) {
        io.execute {
            val w = writer
            val s = socket
            if (s == null || s.isClosed || w == null) { setConnectionStatus("Not connected"); return@execute }
            try {
                w.println(command)
                w.flush()
                if (w.checkError()) setConnectionStatus("Send failed: socket write error")
                else setConnectionStatus("Sent: $command")
            } catch (e: Exception) {
                setConnectionStatus("Send failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }
    }

    private fun closeConnection() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        writer = null
    }

    override fun onDestroy() {
        closeConnection()
        io.shutdownNow()
        super.onDestroy()
    }
}
