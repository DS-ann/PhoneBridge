package com.dsann.phonebridge.hfp

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.dsann.phonebridge.hfpdiagnostic.R
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var report: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var headsetClientProxy: BluetoothProfile? = null
    private var proxyListener: BluetoothProfile.ServiceListener? = null
    private var selectedDevice: BluetoothDevice? = null

    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        report = findViewById(R.id.report)
        findViewById<Button>(R.id.run).setOnClickListener { runDiagnostic() }
        findViewById<Button>(R.id.connect).setOnClickListener { connectHfpClient() }
        runDiagnostic()
    }

    private fun runDiagnostic() {
        val b = adapter
        if (b == null) {
            report.text = "Bluetooth adapter: NOT PRESENT"
            return
        }

        val out = StringBuilder()
        out.appendLine("PhoneBridge HFP Diagnostic – Redmi HFP Client + Service Probe")
        out.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        out.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        out.appendLine()
        out.appendLine("Bluetooth adapter: PRESENT")
        out.appendLine("Enabled: ${b.isEnabled}")
        out.appendLine("Name: ${safeName(b)}")
        out.appendLine()

        out.appendLine("LOCAL Bluetooth UUIDs / service records")
        val localUuids = getLocalUuids(b)
        if (localUuids.isEmpty()) {
            out.appendLine("none/hidden/not exposed to app")
        } else {
            localUuids.forEach { out.appendLine(it) }
            out.appendLine("HFP/HSP local role classification: ${classify(localUuids)}")
        }

        out.appendLine()
        out.appendLine("HFP Client framework")
        val clientId = try {
            BluetoothProfile::class.java.getField("HEADSET_CLIENT").getInt(null)
        } catch (_: Throwable) { -1 }
        out.appendLine("HEADSET_CLIENT profile ID: ${if (clientId >= 0) clientId else "not available"}")
        try {
            Class.forName("android.bluetooth.BluetoothHeadsetClient")
            out.appendLine("BluetoothHeadsetClient class: PRESENT")
        } catch (t: Throwable) {
            out.appendLine("BluetoothHeadsetClient class: NOT PRESENT (${t.javaClass.simpleName})")
        }

        out.appendLine()
        out.appendLine("HFP Client service/proxy")
        out.appendLine("proxy: ${if (headsetClientProxy != null) "READY" else "NOT CONNECTED"}")
        if (selectedDevice != null) {
            out.appendLine("selected device: ${safeDeviceName(selectedDevice!!)}")
        }
        if (clientId >= 0 && headsetClientProxy == null) {
            out.appendLine("requesting HFP Client proxy...")
            requestHfpClientProxy(clientId)
        }

        out.appendLine()
        out.appendLine("System Bluetooth package")
        try {
            val pi = packageManager.getPackageInfo("com.android.bluetooth", 0)
            out.appendLine("installed: yes")
            out.appendLine("versionName: ${pi.versionName}")
            out.appendLine("versionCode: ${pi.longVersionCode}")
            out.appendLine("package: ${pi.packageName}")
        } catch (t: Throwable) {
            out.appendLine("not accessible: ${t.javaClass.simpleName}: ${t.message}")
        }

        out.appendLine()
        out.appendLine("Running-service probes")
        out.appendLine("HfpClientService class probe: ${classProbe("com.android.bluetooth.hfpclient.HeadsetClientService")}")
        out.appendLine("HfpClientService alternate probe: ${classProbe("com.android.bluetooth.hfpclient.HeadsetClientService")}")
        out.appendLine("HeadsetService class probe: ${classProbe("com.android.bluetooth.hfp.HeadsetService")}")
        out.appendLine("Note: ordinary apps cannot reliably enumerate running system services on stock Android; class presence is not proof that a service is enabled.")

        report.text = out.toString()
    }

    private fun requestHfpClientProxy(profileId: Int) {
        try {
            proxyListener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == profileId) {
                        headsetClientProxy = proxy
                        runOnUiThread { appendLine("HFP Client proxy callback: CONNECTED / READY") }
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == profileId) {
                        headsetClientProxy = null
                        runOnUiThread { appendLine("HFP Client proxy callback: DISCONNECTED") }
                    }
                }
            }
            val ok = adapter?.getProfileProxy(this, proxyListener, profileId) ?: false
            appendLine("getProfileProxy(HFP_CLIENT): $ok")
        } catch (t: Throwable) {
            appendLine("getProfileProxy exception: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun connectHfpClient() {
        val b = adapter ?: return
        val target = try {
            b.bondedDevices.firstOrNull { safeDeviceName(it).contains("PHAB", true) || safeDeviceName(it).contains("Lenovo", true) }
        } catch (_: Throwable) { null }
        if (target == null) {
            appendLine("CONNECT: Lenovo PHAB2 Plus not found among bonded devices")
            return
        }
        selectedDevice = target
        appendLine("CONNECT: target=${safeDeviceName(target)} / ${safeAddress(target)}")
        if (headsetClientProxy == null) {
            appendLine("CONNECT: HFP Client proxy not ready; requesting it now")
            val id = try { BluetoothProfile::class.java.getField("HEADSET_CLIENT").getInt(null) } catch (_: Throwable) { -1 }
            if (id >= 0) requestHfpClientProxy(id) else appendLine("CONNECT: HEADSET_CLIENT unavailable")
            handler.postDelayed({ attemptReflectiveConnect(target) }, 1000)
        } else {
            attemptReflectiveConnect(target)
        }
    }

    private fun attemptReflectiveConnect(device: BluetoothDevice) {
        val proxy = headsetClientProxy ?: run {
            appendLine("CONNECT: proxy still not ready")
            return
        }
        try {
            val method = proxy.javaClass.methods.firstOrNull { it.name == "connect" && it.parameterTypes.size == 1 }
            if (method == null) {
                appendLine("CONNECT: no connect(BluetoothDevice) method exposed by proxy class ${proxy.javaClass.name}")
                return
            }
            method.isAccessible = true
            val result = method.invoke(proxy, device)
            appendLine("CONNECT: reflective connect() result=$result")
            val stateMethod = proxy.javaClass.methods.firstOrNull { it.name == "getConnectionState" && it.parameterTypes.size == 1 }
            if (stateMethod != null) {
                stateMethod.isAccessible = true
                val state = stateMethod.invoke(proxy, device)
                appendLine("CONNECT: immediate HFP Client connection state=$state")
                handler.postDelayed({
                    try {
                        val later = stateMethod.invoke(proxy, device)
                        appendLine("CONNECT: state after 2s=$later")
                    } catch (t: Throwable) { appendLine("CONNECT: delayed state error=${t.javaClass.simpleName}: ${t.message}") }
                }, 2000)
            }
        } catch (t: Throwable) {
            appendLine("CONNECT: ${t.javaClass.name}: ${t.message}")
            t.cause?.let { appendLine("CONNECT cause: ${it.javaClass.name}: ${it.message}") }
        }
    }

    private fun classProbe(name: String): String = try {
        Class.forName(name)
        "PRESENT"
    } catch (t: Throwable) { "NOT PRESENT (${t.javaClass.simpleName})" }

    private fun appendLine(s: String) {
        runOnUiThread { report.append("\n$s") }
    }

    private fun getLocalUuids(adapter: BluetoothAdapter): List<String> = try {
        val method = BluetoothAdapter::class.java.getDeclaredMethod("getUuids")
        method.isAccessible = true
        val result = method.invoke(adapter) as? Array<*>
        result?.mapNotNull { r ->
            try { r?.javaClass?.getField("uuid")?.get(r)?.toString() } catch (_: Throwable) { r?.toString() }
        } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    private fun classify(values: List<String>): String {
        val s = values.map { it.lowercase(Locale.US) }
        val r = mutableListOf<String>()
        if (s.any { it.startsWith("0000111e-") }) r += "HF"
        if (s.any { it.startsWith("0000111f-") }) r += "AG"
        if (s.any { it.startsWith("00001108-") }) r += "HSP_HEADSET"
        if (s.any { it.startsWith("00001112-") }) r += "HSP_AG"
        return if (r.isEmpty()) "No standard HFP/HSP UUID visible" else r.joinToString(", ")
    }

    private fun safeName(b: BluetoothAdapter): String = try { b.name ?: "unknown" } catch (_: SecurityException) { "permission denied" }
    private fun safeDeviceName(d: BluetoothDevice): String = try { d.name ?: "unknown" } catch (_: SecurityException) { "permission denied" }
    private fun safeAddress(d: BluetoothDevice): String = try { d.address } catch (_: SecurityException) { "permission denied" }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val id = BluetoothProfile::class.java.getField("HEADSET_CLIENT").getInt(null)
            headsetClientProxy?.let { adapter?.closeProfileProxy(id, it) }
        } catch (_: Throwable) { }
    }
}
