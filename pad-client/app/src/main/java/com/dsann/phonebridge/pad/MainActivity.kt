package com.dsann.phonebridge.pad

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
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
    private lateinit var timer: TextView
    private var callStartedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.rgb(248, 249, 252))
        }

        val title = TextView(this).apply {
            text = "PhoneBridge"
            textSize = 28f
            setTextColor(Color.rgb(30, 35, 45))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Cellular phone • Wi-Fi bridge"
            textSize = 14f
            gravity = Gravity.CENTER
        }
        number = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 22f
            setSingleLine(true)
            gravity = Gravity.CENTER
        }
        val connect = Button(this).apply { text = "Connect to Phab" }
        val call = Button(this).apply { text = "☎  CALL"; textSize = 18f }
        val answer = Button(this).apply { text = "ANSWER" }
        val reject = Button(this).apply { text = "REJECT" }
        val hangup = Button(this).apply { text = "END CALL" }
        val ping = Button(this).apply { text = "PING (test)" }

        connectionStatus = TextView(this).apply {
            text = "● Disconnected"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 8)
        }
        callStatus = TextView(this).apply {
            text = "IDLE"
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 4)
        }
        timer = TextView(this).apply {
            text = "00:00"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(connectionStatus)
        root.addView(callStatus)
        root.addView(timer)
        root.addView(number, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 20, 0, 8) })
        root.addView(call)

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row.addView(answer, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(reject, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(hangup, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        root.addView(connect)
        root.addView(ping)
        setContentView(root)

        connect.setOnClickListener { connectTo("192.168.43.1") }
        ping.setOnClickListener { send("PING") }
        call.setOnClickListener {
            val n = number.text.toString().trim()
            if (n.isNotEmpty()) {
                setCallStatus("DIALING")
                send("DIAL:$n")
            } else setCallStatus("ENTER NUMBER")
        }
        answer.setOnClickListener { send("ANSWER") }
        reject.setOnClickListener { send("REJECT") }
        hangup.setOnClickListener { send("HANGUP") }
    }

    private fun setConnectionStatus(value: String) = runOnUiThread { connectionStatus.text = value }

    private fun setCallStatus(value: String) = runOnUiThread {
        callStatus.text = value
        if (value == "OFFHOOK") callStartedAt = SystemClock.elapsedRealtime()
        if (value == "IDLE") callStartedAt = 0L
    }

    private fun connectTo(host: String) {
        closeConnection()
        setConnectionStatus("● Connecting…")
        io.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, 45821), 5000)
                s.keepAlive = true
                val w = PrintWriter(s.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                socket = s
                writer = w
                setConnectionStatus("● Connected • Phab")
                io.execute {
                    try {
                        while (!s.isClosed) {
                            val line = reader.readLine() ?: break
                            when {
                                line.startsWith("CALL_STATE:") -> setCallStatus(line.substringAfter(':'))
                                line == "PONG" -> setConnectionStatus("● Connected • PONG")
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (socket === s) {
                            socket = null
                            writer = null
                            setConnectionStatus("● Disconnected")
                        }
                    }
                }
            } catch (e: Exception) {
                setConnectionStatus("● Connection failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun send(command: String) {
        io.execute {
            val w = writer
            val s = socket
            if (s == null || s.isClosed || w == null) { setConnectionStatus("● Not connected"); return@execute }
            try {
                w.println(command)
                w.flush()
                if (w.checkError()) setConnectionStatus("● Send failed")
            } catch (e: Exception) {
                setConnectionStatus("● Send failed: ${e.javaClass.simpleName}")
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
