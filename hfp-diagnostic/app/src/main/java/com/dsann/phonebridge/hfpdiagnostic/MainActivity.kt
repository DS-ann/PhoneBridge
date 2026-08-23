package com.dsann.phonebridge.hfpdiagnostic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var report: TextView
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var headsetProxy: BluetoothProfile? = null
    private var a2dpProxy: BluetoothProfile? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = proxy
                BluetoothProfile.A2DP -> a2dpProxy = proxy
            }
            runDiagnostics("Profile callback: ${profileName(profile)} connected")
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = null
                BluetoothProfile.A2DP -> a2dpProxy = null
            }
            appendLine("Profile callback: ${profileName(profile)} disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestBluetoothPermissionsIfNeeded()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "PhoneBridge HFP Diagnostic"
            textSize = 24f
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val subtitle = TextView(this).apply {
            text = "Redmi Pad 2 / Android 16\nRead-only Bluetooth/HFP capability test"
            textSize = 15f
            setPadding(0, 12, 0, 20)
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))

        val run = Button(this).apply {
            text = "RUN HFP DIAGNOSTICS"
            setOnClickListener { runDiagnostics("Manual test") }
        }
        root.addView(run, LinearLayout.LayoutParams(-1, -2))

        val copy = Button(this).apply {
            text = "COPY REPORT"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PhoneBridge HFP report", report.text))
            }
        }
        root.addView(copy, LinearLayout.LayoutParams(-1, -2))

        report = TextView(this).apply {
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            setPadding(0, 20, 0, 0)
        }
        root.addView(report, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 42)
        }
    }

    @SuppressLint("MissingPermission")
    private fun runDiagnostics(reason: String) {
        report.text = ""
        appendLine("PhoneBridge HFP Diagnostic")
        appendLine("Reason: $reason")
        appendLine("Time: ${System.currentTimeMillis()}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("")

        val bt = adapter
        if (bt == null) {
            appendLine("Bluetooth adapter: NOT PRESENT")
            return
        }

        appendLine("Bluetooth adapter: PRESENT")
        appendLine("Enabled: ${bt.isEnabled}")
        appendLine("Name: ${safe { bt.name }}")
        appendLine("Address: ${safe { bt.address }}")
        appendLine("Scan mode: ${safe { bt.scanMode }}")
        appendLine("")

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        appendLine("AudioManager")
        appendLine("mode: ${audio.mode}")
        appendLine("isBluetoothScoAvailableOffCall: ${audio.isBluetoothScoAvailableOffCall}")
        appendLine("isBluetoothScoOn: ${audio.isBluetoothScoOn}")
        appendLine("")

        appendLine("Paired devices")
        val bonded = try { bt.bondedDevices } catch (e: Exception) { emptySet() }
        if (bonded.isEmpty()) appendLine("  none")
        for (device in bonded) {
            appendLine("  ${device.name ?: "(unnamed)"} / ${device.address}")
            appendLine("    class: ${device.bluetoothClass?.deviceClass}")
            appendLine("    bondState: ${device.bondState}")
        }
        appendLine("")

        appendLine("Public profile proxies")
        requestProxy(bt, BluetoothProfile.HEADSET, "HEADSET")
        requestProxy(bt, BluetoothProfile.A2DP, "A2DP")
        appendLine("")

        appendLine("HFP Client / HF-role probe")
        probeHeadsetClient(bt)
        appendLine("")

        appendLine("System Bluetooth package")
        try {
            val info = packageManager.getPackageInfo("com.android.bluetooth", 0)
            appendLine("  installed: yes")
            appendLine("  versionName: ${info.versionName}")
            appendLine("  versionCode: ${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode}")
        } catch (e: Exception) {
            appendLine("  installed: no / inaccessible (${e.javaClass.simpleName})")
        }

        appendLine("")
        appendLine("Dumpsys probe (may be permission-denied on stock Android)")
        appendLine(runCommand("dumpsys", "bluetooth_manager").take(5000))
    }

    @SuppressLint("MissingPermission")
    private fun requestProxy(bt: BluetoothAdapter, profile: Int, label: String) {
        try {
            val ok = bt.getProfileProxy(this, listener, profile)
            appendLine("$label getProfileProxy(): $ok")
            appendLine("$label connection state: ${profileState(bt.getProfileConnectionState(profile))}")
        } catch (e: SecurityException) {
            appendLine("$label: SecurityException: ${e.message}")
        } catch (e: Exception) {
            appendLine("$label: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun probeHeadsetClient(bt: BluetoothAdapter) {
        try {
            val field: Field = BluetoothProfile::class.java.getDeclaredField("HEADSET_CLIENT")
            field.isAccessible = true
            val profile = field.getInt(null)
            appendLine("reflected BluetoothProfile.HEADSET_CLIENT = $profile")

            try {
                val ok = bt.getProfileProxy(this, listener, profile)
                appendLine("getProfileProxy(HFP_CLIENT): $ok")
                appendLine("connection state: ${profileState(bt.getProfileConnectionState(profile))}")
                appendLine("Interpretation: a true result means the framework accepted the profile ID; it does NOT by itself prove third-party HFP-client control is permitted.")
            } catch (e: SecurityException) {
                appendLine("getProfileProxy(HFP_CLIENT): SecurityException: ${e.message}")
            } catch (e: Throwable) {
                appendLine("getProfileProxy(HFP_CLIENT): ${e.javaClass.simpleName}: ${e.message}")
            }
        } catch (e: Throwable) {
            appendLine("HEADSET_CLIENT constant inaccessible: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val clazz = Class.forName("android.bluetooth.BluetoothHeadsetClient")
            appendLine("android.bluetooth.BluetoothHeadsetClient class: PRESENT")
            appendLine("class loader: ${clazz.classLoader}")
        } catch (e: Throwable) {
            appendLine("android.bluetooth.BluetoothHeadsetClient class: NOT ACCESSIBLE (${e.javaClass.simpleName})")
        }
    }

    private fun runCommand(vararg args: String): String {
        return try {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            if (text.isBlank()) "<no output>" else text
        } catch (e: Exception) {
            "<${e.javaClass.simpleName}: ${e.message}>"
        }
    }

    private fun profileState(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "UNKNOWN($state)"
    }

    private fun profileName(profile: Int): String = when (profile) {
        BluetoothProfile.HEADSET -> "HEADSET"
        BluetoothProfile.A2DP -> "A2DP"
        else -> "PROFILE($profile)"
    }

    private fun safe(block: () -> Any?): String = try {
        block()?.toString() ?: "null"
    } catch (e: Exception) {
        "<${e.javaClass.simpleName}>"
    }

    private fun appendLine(value: String) {
        report.append(value)
        report.append("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } } catch (_: Exception) {}
        try { a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } } catch (_: Exception) {}
    }
}
