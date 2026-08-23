package com.dsann.phonebridge.hfp

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var report: TextView
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        report = findViewById(R.id.report)
        findViewById<Button>(R.id.run).setOnClickListener { runDiagnostic() }
        runDiagnostic()
    }

    private fun runDiagnostic() {
        val b = adapter
        if (b == null) {
            report.text = "Bluetooth adapter: NOT PRESENT"
            return
        }
        val out = StringBuilder()
        out.appendLine("PhoneBridge HFP Diagnostic – Redmi Local Profile")
        out.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        out.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        out.appendLine()
        out.appendLine("Bluetooth adapter: PRESENT")
        out.appendLine("Enabled: ${b.isEnabled}")
        out.appendLine("Name: ${safeName(b)}")
        out.appendLine()
        out.appendLine("LOCAL Bluetooth UUIDs / service records")
        val uuids = try { b.uuids } catch (_: SecurityException) { null }
        if (uuids.isNullOrEmpty()) {
            out.appendLine("none/hidden/not exposed to app")
        } else {
            uuids.forEach { out.appendLine(it.uuid.toString()) }
            out.appendLine()
            out.appendLine("HFP/HSP local role classification: ${classify(uuids.map { it.uuid.toString() })}")
        }
        out.appendLine()
        out.appendLine("Role reference")
        out.appendLine("HFP Hands-Free (HF): 0000111e-0000-1000-8000-00805f9b34fb")
        out.appendLine("HFP Audio Gateway (AG): 0000111f-0000-1000-8000-00805f9b34fb")
        out.appendLine("HSP Headset: 00001108-0000-1000-8000-00805f9b34fb")
        out.appendLine("HSP Audio Gateway: 00001112-0000-1000-8000-00805f9b34fb")
        out.appendLine()
        out.appendLine("PAIRED DEVICES — remote UUIDs")
        val bonded = try { b.bondedDevices } catch (_: SecurityException) { emptySet() }
        if (bonded.isEmpty()) out.appendLine("none/hidden")
        for (d in bonded) {
            out.appendLine("${safeDeviceName(d)} / ${safeAddress(d)} / bond=${d.bondState}")
            val ru = try { d.uuids } catch (_: SecurityException) { null }
            if (ru.isNullOrEmpty()) {
                out.appendLine("  Remote UUIDs: none/hidden")
            } else {
                out.appendLine("  Remote UUIDs: ${ru.joinToString(", ") { it.uuid.toString() }}")
                out.appendLine("  HFP/HSP classification: ${classify(ru.map { it.uuid.toString() })}")
            }
        }
        out.appendLine()
        out.appendLine("HFP Client framework probe")
        try {
            val f = BluetoothProfile::class.java.getField("HEADSET_CLIENT")
            out.appendLine("HEADSET_CLIENT profile ID: ${f.getInt(null)}")
            out.appendLine("BluetoothHeadsetClient class: PRESENT")
        } catch (t: Throwable) {
            out.appendLine("HFP Client framework: ${t.javaClass.simpleName}: ${t.message}")
        }
        out.appendLine()
        out.appendLine("System Bluetooth package")
        try {
            val pi = packageManager.getPackageInfo("com.android.bluetooth", 0)
            out.appendLine("installed: yes")
            out.appendLine("versionName: ${pi.versionName}")
            out.appendLine("versionCode: ${pi.longVersionCode}")
        } catch (t: Throwable) {
            out.appendLine("not accessible: ${t.javaClass.simpleName}")
        }
        report.text = out.toString()
    }

    private fun classify(values: List<String>): String {
        val s = values.map { it.lowercase(Locale.US) }
        val hf = s.any { it.startsWith("0000111e-") }
        val ag = s.any { it.startsWith("0000111f-") }
        val hspHeadset = s.any { it.startsWith("00001108-") }
        val hspAg = s.any { it.startsWith("00001112-") }
        val r = mutableListOf<String>()
        if (hf) r += "HF"
        if (ag) r += "AG"
        if (hspHeadset) r += "HSP_HEADSET"
        if (hspAg) r += "HSP_AG"
        return if (r.isEmpty()) "No standard HFP/HSP UUID visible" else r.joinToString(", ")
    }

    private fun safeName(b: BluetoothAdapter): String = try { b.name ?: "unknown" } catch (_: SecurityException) { "permission denied" }
    private fun safeDeviceName(d: BluetoothDevice): String = try { d.name ?: "unknown" } catch (_: SecurityException) { "permission denied" }
    private fun safeAddress(d: BluetoothDevice): String = try { d.address } catch (_: SecurityException) { "permission denied" }
}
