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
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Field

class MainActivity : Activity() {
    private lateinit var report: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var headsetProxy: BluetoothProfile? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var hfpClientProxy: BluetoothProfile? = null
    private var hfpClientProfileId: Int = -1
    private var bondedDevices: List<BluetoothDevice> = emptyList()

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = proxy
                BluetoothProfile.A2DP -> a2dpProxy = proxy
                hfpClientProfileId -> hfpClientProxy = proxy
            }
            appendLine("Profile callback: ${profileName(profile)} connected")
            if (profile == hfpClientProfileId) {
                appendLine("HFP Client proxy class: ${proxy.javaClass.name}")
                appendLine("HFP Client current state: ${stateForSelectedDevice()}")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = null
                BluetoothProfile.A2DP -> a2dpProxy = null
                hfpClientProfileId -> hfpClientProxy = null
            }
            appendLine("Profile callback: ${profileName(profile)} disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestBluetoothPermissionsIfNeeded()
        refreshDevices()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "PhoneBridge HFP Diagnostic v2"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Redmi Pad 2 / Android 16\nActive HFP-HF connection tester"
            textSize = 15f
            setPadding(0, 12, 0, 16)
        })

        root.addView(TextView(this).apply { text = "Select paired device:" })
        deviceSpinner = Spinner(this)
        root.addView(deviceSpinner, LinearLayout.LayoutParams(-1, -2))

        val refresh = Button(this).apply {
            text = "REFRESH PAIRED DEVICES"
            setOnClickListener { refreshDevices() }
        }
        root.addView(refresh)

        connectButton = Button(this).apply {
            text = "CONNECT HFP CLIENT"
            setOnClickListener { connectHfpClient() }
        }
        root.addView(connectButton)

        disconnectButton = Button(this).apply {
            text = "DISCONNECT HFP CLIENT"
            setOnClickListener { disconnectHfpClient() }
        }
        root.addView(disconnectButton)

        val audio = Button(this).apply {
            text = "CONNECT / DISCONNECT SCO AUDIO"
            setOnClickListener { toggleSco() }
        }
        root.addView(audio)

        val run = Button(this).apply {
            text = "RUN DIAGNOSTICS"
            setOnClickListener { runDiagnostics("Manual test") }
        }
        root.addView(run)

        val copy = Button(this).apply {
            text = "COPY REPORT"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PhoneBridge HFP report", report.text))
            }
        }
        root.addView(copy)

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
    private fun refreshDevices() {
        val bt = adapter ?: return
        bondedDevices = try { bt.bondedDevices.toList().sortedBy { it.name ?: it.address } } catch (_: Exception) { emptyList() }
        val labels = bondedDevices.map { "${it.name ?: "(unnamed)"} / ${it.address}" }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        appendLine("Paired-device list refreshed: ${bondedDevices.size} device(s)")
    }

    private fun selectedDevice(): BluetoothDevice? =
        if (bondedDevices.isNotEmpty() && deviceSpinner.selectedItemPosition in bondedDevices.indices)
            bondedDevices[deviceSpinner.selectedItemPosition] else null

    @SuppressLint("MissingPermission")
    private fun connectHfpClient() {
        val device = selectedDevice()
        if (device == null) { appendLine("CONNECT: no paired device selected"); return }
        if (hfpClientProxy == null) {
            appendLine("CONNECT: HFP Client proxy is not ready; requesting it now")
            requestHfpClientProxy()
            return
        }
        invokeProfileMethod("connect", device)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectHfpClient() {
        val device = selectedDevice()
        if (device == null) { appendLine("DISCONNECT: no paired device selected"); return }
        invokeProfileMethod("disconnect", device)
    }

    private fun invokeProfileMethod(methodName: String, device: BluetoothDevice) {
        val proxy = hfpClientProxy ?: run { appendLine("$methodName: HFP Client proxy unavailable"); return }
        try {
            val method = proxy.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1 && it.parameterTypes[0] == BluetoothDevice::class.java
            }
            if (method == null) {
                appendLine("$methodName(): method not found on ${proxy.javaClass.name}")
                appendLine("Available methods: ${proxy.javaClass.methods.map { it.name }.distinct().sorted().joinToString(", ")}")
                return
            }
            method.isAccessible = true
            val result = method.invoke(proxy, device)
            appendLine("$methodName(${device.name ?: device.address}) returned: $result")
            appendLine("Current selected-device state: ${stateForSelectedDevice()}")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            appendLine("$methodName() FAILED: ${cause.javaClass.name}: ${cause.message}")
            appendLine("This may indicate Android hidden-API or Bluetooth service permission enforcement.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleSco() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (audio.isBluetoothScoOn) {
                audio.stopBluetoothSco()
                appendLine("SCO stop requested")
            } else {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
                appendLine("SCO start requested; mode=MODE_IN_COMMUNICATION")
            }
            appendLine("SCO now reported on=${audio.isBluetoothScoOn}")
        } catch (e: Throwable) {
            appendLine("SCO operation failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestHfpClientProxy() {
        val bt = adapter ?: return
        try {
            val field: Field = BluetoothProfile::class.java.getDeclaredField("HEADSET_CLIENT")
            field.isAccessible = true
            hfpClientProfileId = field.getInt(null)
            appendLine("HEADSET_CLIENT profile ID = $hfpClientProfileId")
            val ok = bt.getProfileProxy(this, listener, hfpClientProfileId)
            appendLine("getProfileProxy(HFP_CLIENT): $ok")
        } catch (e: Throwable) {
            appendLine("HFP Client proxy request failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun runDiagnostics(reason: String) {
        report.text = ""
        appendLine("PhoneBridge HFP Diagnostic v2")
        appendLine("Reason: $reason")
        appendLine("Time: ${System.currentTimeMillis()}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("")
        val bt = adapter ?: run { appendLine("Bluetooth adapter: NOT PRESENT"); return }
        appendLine("Bluetooth adapter: PRESENT")
        appendLine("Enabled: ${bt.isEnabled}")
        appendLine("Name: ${safe { bt.name }}")
        appendLine("Address: ${safe { bt.address }}")
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        appendLine("Audio mode: ${audio.mode}")
        appendLine("SCO available off-call: ${audio.isBluetoothScoAvailableOffCall}")
        appendLine("SCO on: ${audio.isBluetoothScoOn}")
        appendLine("")
        appendLine("Paired devices")
        for (device in bondedDevices.ifEmpty { try { bt.bondedDevices.toList() } catch (_: Exception) { emptyList() } }) {
            appendLine("  ${device.name ?: "(unnamed)"} / ${device.address} / bond=${device.bondState}")
        }
        appendLine("")
        appendLine("HFP Client")
        if (hfpClientProfileId < 0) requestHfpClientProxy()
        appendLine("profile ID: $hfpClientProfileId")
        appendLine("proxy: ${hfpClientProxy?.javaClass?.name ?: "not connected"}")
        appendLine("selected state: ${stateForSelectedDevice()}")
        appendLine("")
        appendLine("System Bluetooth package")
        try {
            val info = packageManager.getPackageInfo("com.android.bluetooth", 0)
            appendLine("installed: yes")
            appendLine("versionName: ${info.versionName}")
            appendLine("versionCode: ${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode}")
        } catch (e: Exception) { appendLine("unavailable: ${e.javaClass.simpleName}: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun stateForSelectedDevice(): String {
        val device = selectedDevice() ?: return "NO_DEVICE"
        val proxy = hfpClientProxy ?: return "NO_PROXY"
        return try {
            val method = proxy.javaClass.methods.firstOrNull {
                it.name == "getConnectionState" && it.parameterTypes.size == 1 && it.parameterTypes[0] == BluetoothDevice::class.java
            } ?: return "METHOD_UNAVAILABLE"
            method.isAccessible = true
            profileState((method.invoke(proxy, device) as Number).toInt())
        } catch (e: Throwable) { "ERROR(${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName})" }
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
        hfpClientProfileId -> "HFP_CLIENT"
        else -> "PROFILE($profile)"
    }

    private fun safe(block: () -> Any?): String = try { block()?.toString() ?: "null" } catch (e: Exception) { "<${e.javaClass.simpleName}>" }

    private fun appendLine(value: String) { report.append(value); report.append("\n") }

    override fun onDestroy() {
        super.onDestroy()
        try { headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } } catch (_: Exception) {}
        try { a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } } catch (_: Exception) {}
        try { if (hfpClientProfileId >= 0) hfpClientProxy?.let { adapter?.closeProfileProxy(hfpClientProfileId, it) } } catch (_: Exception) {}
    }
}
