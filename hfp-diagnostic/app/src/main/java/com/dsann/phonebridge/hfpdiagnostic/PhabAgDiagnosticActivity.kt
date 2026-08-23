package com.dsann.phonebridge.hfpdiagnostic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.lang.reflect.Method
import java.util.UUID

class PhabAgDiagnosticActivity : Activity() {
    private val bt: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private lateinit var report: TextView
    private lateinit var spinner: Spinner
    private var devices: List<BluetoothDevice> = emptyList()
    private var headsetProxy: BluetoothHeadsetCompat? = null

    private interface BluetoothHeadsetCompat {
        fun state(device: BluetoothDevice): Int
        fun className(): String
        fun raw(): Any
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = object : BluetoothHeadsetCompat {
                    override fun state(device: BluetoothDevice): Int =
                        try { proxy.getConnectionState(device) } catch (_: Throwable) { -1 }
                    override fun className(): String = proxy.javaClass.name
                    override fun raw(): Any = proxy
                }
                append("HEADSET/Hands-Free profile proxy CONNECTED: ${proxy.javaClass.name}")
                append("Selected HEADSET state: ${selectedHeadsetState()}")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                append("HEADSET/Hands-Free profile proxy DISCONNECTED")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissionsIfNeeded()
        refreshDevices()
        requestHeadsetProxy()
        runDiagnostics("Initial Phab AG inspection")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "PhoneBridge HFP Diagnostic – Phab AG"
            textSize = 23f
        })
        root.addView(TextView(this).apply {
            text = "Phab 2 Plus / HFP Audio Gateway inspection"
            textSize = 15f
            setPadding(0, 10, 0, 16)
        })
        root.addView(TextView(this).apply { text = "Select paired device:" })
        spinner = Spinner(this)
        root.addView(spinner, ViewGroup.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "REFRESH PAIRED DEVICES"
            setOnClickListener { refreshDevices() }
        })
        root.addView(Button(this).apply {
            text = "CHECK HFP AG"
            setOnClickListener { runDiagnostics("Manual AG check") }
        })
        root.addView(Button(this).apply {
            text = "COPY REPORT"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PhoneBridge Phab HFP AG report", report.text))
            }
        })
        report = TextView(this).apply {
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            setPadding(0, 20, 0, 0)
        }
        root.addView(report, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 77)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        devices = try { bt?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList() }
        catch (_: Throwable) { emptyList() }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.name ?: "(unnamed)"} / ${it.address}" }
        )
        append("Paired-device list refreshed: ${devices.size} device(s)")
    }

    @SuppressLint("MissingPermission")
    private fun requestHeadsetProxy() {
        val a = bt ?: return
        try {
            val ok = a.getProfileProxy(this, serviceListener, BluetoothProfile.HEADSET)
            append("getProfileProxy(HEADSET/AG): $ok")
        } catch (e: Throwable) {
            append("HEADSET proxy request FAILED: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun selected(): BluetoothDevice? =
        if (spinner.selectedItemPosition in devices.indices) devices[spinner.selectedItemPosition] else null

    @SuppressLint("MissingPermission")
    private fun selectedHeadsetState(): String {
        val d = selected() ?: return "NO_DEVICE"
        val p = headsetProxy ?: return "NO_PROXY"
        return when (p.state(d)) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "UNKNOWN"
        }
    }

    @SuppressLint("MissingPermission")
    private fun runDiagnostics(reason: String) {
        report.text = ""
        append("PhoneBridge HFP Diagnostic – Phab AG")
        append("Reason: $reason")
        append("Time: ${System.currentTimeMillis()}")
        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append("")
        val a = bt ?: run { append("Bluetooth adapter: NOT PRESENT"); return }
        append("Bluetooth adapter: PRESENT")
        append("Enabled: ${a.isEnabled}")
        append("Name: ${safe { a.name }}")
        append("Address: ${safe { a.address }}")
        append("Scan mode: ${safe { a.scanMode }}")
        append("")
        append("Local Bluetooth UUIDs / service records")
        appendLocalUuids(a)
        append("")
        append("Paired devices")
        for (d in devices.ifEmpty { try { a.bondedDevices.toList() } catch (_: Throwable) { emptyList() } }) {
            append("${d.name ?: "(unnamed)"} / ${d.address} / class=${safe { d.bluetoothClass?.deviceClass }} / bond=${d.bondState}")
            append("  Remote UUIDs: ${remoteUuids(d)}")
            append("  HFP/HSP UUID classification: ${classifyUuids(d)}")
        }
        append("")
        append("HFP Audio Gateway / HEADSET profile")
        append("HEADSET profile ID: ${BluetoothProfile.HEADSET}")
        append("proxy: ${headsetProxy?.className() ?: "not connected"}")
        append("selected device: ${selected()?.name ?: "none"}")
        append("selected HEADSET state: ${selectedHeadsetState()}")
        append("Interpretation: Android phone-side HeadsetService is the HFP Audio Gateway role in the standard AOSP architecture.")
        append("")
        append("HFP role UUID reference")
        append("HFP Hands-Free: 0000111e-0000-1000-8000-00805f9b34fb")
        append("HFP Audio Gateway: 0000111f-0000-1000-8000-00805f9b34fb")
        append("HSP Headset: 00001108-0000-1000-8000-00805f9b34fb")
        append("HSP Audio Gateway: 00001112-0000-1000-8000-00805f9b34fb")
        append("")
        append("System Bluetooth package")
        try {
            val info = packageManager.getPackageInfo("com.android.bluetooth", 0)
            append("installed: yes")
            append("versionName: ${info.versionName}")
            append("versionCode: ${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode}")
        } catch (e: Throwable) {
            append("unavailable: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun appendLocalUuids(a: BluetoothAdapter) {
        try {
            val m: Method = BluetoothAdapter::class.java.getMethod("getUuids")
            val value = m.invoke(a) as? Array<*>
            if (value.isNullOrEmpty()) append("No local UUIDs returned by framework")
            else value.forEach { append("${it}") }
        } catch (e: Throwable) {
            append("Local UUID query unavailable: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun remoteUuids(d: BluetoothDevice): String = try {
        val value = d.uuids
        if (value.isNullOrEmpty()) "none/hidden" else value.joinToString(", ") { it.uuid.toString() }
    } catch (e: Throwable) { "ERROR(${e.javaClass.simpleName})" }

    @SuppressLint("MissingPermission")
    private fun classifyUuids(d: BluetoothDevice): String {
        val set = try { d.uuids?.map { it.uuid.toString().lowercase() }?.toSet() ?: emptySet() } catch (_: Throwable) { emptySet() }
        val labels = mutableListOf<String>()
        if (set.contains("0000111e-0000-1000-8000-00805f9b34fb")) labels += "HF"
        if (set.contains("0000111f-0000-1000-8000-00805f9b34fb")) labels += "AG"
        if (set.contains("00001108-0000-1000-8000-00805f9b34fb")) labels += "HSP_HEADSET"
        if (set.contains("00001112-0000-1000-8000-00805f9b34fb")) labels += "HSP_AG"
        return if (labels.isEmpty()) "No standard HFP/HSP UUID visible" else labels.joinToString(", ")
    }

    private fun safe(block: () -> Any?): String = try { block()?.toString() ?: "null" } catch (e: Throwable) { "<${e.javaClass.simpleName}>" }
    private fun append(s: String) { report.append(s); report.append("\n") }

    override fun onDestroy() {
        super.onDestroy()
        try { headsetProxy?.let { bt?.closeProfileProxy(BluetoothProfile.HEADSET, it.raw() as BluetoothProfile) } } catch (_: Throwable) {}
    }
}
