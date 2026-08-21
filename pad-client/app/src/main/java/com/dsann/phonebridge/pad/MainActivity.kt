package com.dsann.phonebridge.pad

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val ui = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private lateinit var connectionStatus: TextView
    private lateinit var callStatus: TextView
    private lateinit var resultStatus: TextView
    private lateinit var number: EditText
    private lateinit var timer: TextView
    private var callStartedAt = 0L

    private val timerTask = object : Runnable {
        override fun run() {
            val started = callStartedAt
            timer.text = if (started == 0L) "00:00" else formatDuration(SystemClock.elapsedRealtime() - started)
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ui.post(timerTask)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.rgb(248, 249, 252))
        }
        root.addView(TextView(this).apply {
            text = "PhoneBridge"
            textSize = 28f
            setTextColor(Color.rgb(30, 35, 45))
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Cellular phone • Wi-Fi bridge"
            textSize = 14f
            gravity = Gravity.CENTER
        })

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
        resultStatus = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 8)
        }
        number = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 22f
            setSingleLine(true)
            gravity = Gravity.CENTER
        }

        root.addView(connectionStatus)
        root.addView(callStatus)
        root.addView(timer)
        root.addView(resultStatus)
        root.addView(number, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 20, 0, 8) })

        val call = Button(this).apply { text = "☎  CALL"; textSize = 18f }
        root.addView(call)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val answer = Button(this).apply { text = "ANSWER" }
        val reject = Button(this).apply { text = "REJECT" }
        val hangup = Button(this).apply { text = "END CALL" }
        row.addView(answer, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(reject, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(hangup, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        val connect = Button(this).apply { text = "Connect to Phab" }
        val ping = Button(this).apply { text = "PING (test)" }
        root.addView(connect)
        root.addView(ping)
        setContentView(root)

        connect.setOnClickListener { connectTo("192.168.43.1") }
        ping.setOnClickListener { send("PING") }
        call.setOnClickListener {
            val n = number.text.toString().trim()
            if (n.isEmpty()) {
                setResult("Enter a phone number")
            } else {
                setCallStatus("DIALING")
                send("DIAL:$n")
            }
        }
        answer.setOnClickListener { send("ANSWER") }
        reject.setOnClickListener { send("REJECT") }
        hangup.setOnClickListener { send("HANGUP") }
    }

    private fun setConnectionStatus(value: String) = runOnUiThread { connectionStatus.text = value }

    private fun setResult(value: String) = runOnUiThread { resultStatus.text = value }

    private fun setCallStatus(value: String) = runOnUiThread {
        callStatus.text = value
        when (value) {
            "OFFHOOK" -> if (callStartedAt == 0L) callStartedAt = SystemClock.elapsedRealtime()
            "IDLE" -> callStartedAt = 0L
        }
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
                io.execute { readLoop(s, reader) }
            } catch (e: Exception) {
                setConnectionStatus("● Connection failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun readLoop(s: Socket, reader: BufferedReader) {
        try {
            while (!s.isClosed) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("CALL_STATE:") -> setCallStatus(line.substringAfter(':'))
                    line.startsWith("AUDIO_ROUTE:") -> setResult("Audio: ${line.substringAfter(':')}")
                    line == "PONG" -> setConnectionStatus("● Connected • PONG")
                    line.startsWith("OK:") -> setResult("✓ ${line.substringAfter(':')}")
                    line.startsWith("ERROR:") -> setResult("✕ ${line.substringAfter(':')}")
                    line.startsWith("READY") -> setResult("Bridge ready")
                    else -> setResult(line)
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

    private fun send(command: String) {
        io.execute {
            val w = writer
            val s = socket
            if (s == null || s.isClosed || w == null) {
                setConnectionStatus("● Not connected")
                return@execute
            }
            try {
                w.println(command)
                w.flush()
                if (w.checkError()) setConnectionStatus("● Send failed")
            } catch (e: Exception) {
                setConnectionStatus("● Send failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun closeConnection() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        writer = null
    }

    override fun onDestroy() {
        ui.removeCallbacks(timerTask)
        closeConnection()
        io.shutdownNow()
        super.onDestroy()
    }
}
