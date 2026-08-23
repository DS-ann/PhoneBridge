package com.dsann.phonebridge.hfp

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
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
    private var clientProfileId = -1

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
        val b = adapter ?: run { report.text = "Bluetooth adapter: NOT PRESENT"; return }
        val out = StringBuilder()
        out.appendLine("PhoneBridge HFP Diagnostic v3")
        out.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        out.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        out.appendLine()
        out.appendLine("Bluetooth adapter: PRESENT")
        out.appendLine("Enabled: ${b.isEnabled}")
        out.appendLine("Name: ${safeName(b)}")
        out.appendLine()
        out.appendLine("LOCAL Bluetooth UUIDs / service records")
        val uuids = getLocalUuids(b)
        if (uuids.isEmpty()) out.appendLine("none/hidden/not exposed to app")
        else { uuids.forEach(out::appendLine); out.appendLine("HFP/HSP local role classification: ${classify(uuids)}") }
        out.appendLine()
        out.appendLine("HFP Client framework")
        clientProfileId = try { BluetoothProfile::class.java.getField("HEADSET_CLIENT").getInt(null) } catch (_: Throwable) { -1 }
        out.appendLine("profile ID: ${if (clientProfileId >= 0) clientProfileId else "not available"}")
        try { Class.forName("android.bluetooth.BluetoothHeadsetClient"); out.appendLine("BluetoothHeadsetClient class: PRESENT") }
        catch (t: Throwable) { out.appendLine("BluetoothHeadsetClient class: NOT PRESENT (${t.javaClass.simpleName})") }
        out.appendLine()
        out.appendLine("HFP Client proxy")
        out.appendLine("current proxy: ${if (headsetClientProxy != null) "READY" else "NOT CONNECTED"}")
        out.appendLine("Requesting proxy now...")
        if (clientProfileId >= 0) requestHfpClientProxy(clientProfileId) else out.appendLine("HEADSET_CLIENT unavailable")
        out.appendLine()
        out.appendLine("System Bluetooth package")
        try {
            val pi = packageManager.getPackageInfo("com.android.bluetooth", 0)
            out.appendLine("installed: yes")
            out.appendLine("versionName: ${pi.versionName}")
            out.appendLine("versionCode: ${pi.longVersionCode}")
        } catch (t: Throwable) { out.appendLine("not accessible: ${t.javaClass.simpleName}: ${t.message}") }
        out.appendLine()
        out.appendLine("Service class probes")
        out.appendLine("HeadsetClientService: ${classProbe("com.android.bluetooth.hfpclient.HeadsetClientService")}")
        out.appendLine("HeadsetService: ${classProbe("com.android.bluetooth.hfp.HeadsetService")}")
        out.appendLine("Note: class presence does not prove the service is running.")
        report.text = out.toString()
    }

    private fun requestHfpClientProxy(profileId: Int) {
        try {
            proxyListener?.let { headsetClientProxy?.let { p -> adapter?.closeProfileProxy(profileId, p) } }
            proxyListener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != profileId) return
                    headsetClientProxy = proxy
                    appendLine("PROXY CALLBACK: CONNECTED / READY")
                    appendLine("PROXY class: ${proxy.javaClass.name}")
                    appendLine("PROXY methods: ${proxy.javaClass.methods.filter { it.name == "connect" || it.name == "getConnectionState" }.joinToString { it.toGenericString() }}")
                    selectedDevice?.let { handler.postDelayed({ performConnectionProbe(it) }, 300) }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == profileId) { headsetClientProxy = null; appendLine("PROXY CALLBACK: DISCONNECTED") }
                }
            }
            val ok = adapter?.getProfileProxy(this, proxyListener, profileId) ?: false
            appendLine("getProfileProxy(HFP_CLIENT): $ok")
        } catch (t: Throwable) { appendLine("getProfileProxy exception: ${t.javaClass.name}: ${t.message}") }
    }

    private fun connectHfpClient() {
        val b = adapter ?: return
        val target = try { b.bondedDevices.firstOrNull { safeDeviceName(it).contains("PHAB", true) || safeDeviceName(it).contains("Lenovo", true) } } catch (_: Throwable) { null }
        if (target == null) { appendLine("CONNECT: Lenovo PHAB2 Plus not found among bonded devices"); return }
        selectedDevice = target
        appendLine("CONNECT TEST: target=${safeDeviceName(target)} / ${safeAddress(target)}")
        appendLine("CONNECT TEST: starting proxy request")
        if (headsetClientProxy != null) performConnectionProbe(target)
        else if (clientProfileId >= 0) requestHfpClientProxy(clientProfileId) else appendLine("CONNECT TEST: HEADSET_CLIENT unavailable")
    }

    private fun performConnectionProbe(device: BluetoothDevice) {
        val proxy = headsetClientProxy ?: run { appendLine("CONNECT: proxy still not ready"); return }
        appendLine("CONNECT: proxy READY; beginning method probe")
        val connectMethod = proxy.javaClass.methods.firstOrNull { it.name == "connect" && it.parameterTypes.size == 1 && BluetoothDevice::class.java.isAssignableFrom(it.parameterTypes[0]) }
        val stateMethod = proxy.javaClass.methods.firstOrNull { it.name == "getConnectionState" && it.parameterTypes.size == 1 && BluetoothDevice::class.java.isAssignableFrom(it.parameterTypes[0]) }
        appendLine("CONNECT: connect(BluetoothDevice) method=${if (connectMethod != null) "FOUND" else "NOT FOUND"}")
        appendLine("CONNECT: getConnectionState(BluetoothDevice)=${if (stateMethod != null) "FOUND" else "NOT FOUND"}")
        try {
            connectMethod?.isAccessible = true
            val result = connectMethod?.invoke(proxy, device)
            appendLine("CONNECT: connect() result=$result")
        } catch (t: Throwable) {
            appendLine("CONNECT: connect() exception=${t.javaClass.name}: ${t.message}")
            t.cause?.let { appendLine("CONNECT: cause=${it.javaClass.name}: ${it.message}") }
        }
        readState(proxy, stateMethod, device, "immediate")
        handler.postDelayed({ readState(proxy, stateMethod, device, "2s") }, 2000)
        handler.postDelayed({ readState(proxy, stateMethod, device, "5s") }, 5000)
    }

    private fun readState(proxy: BluetoothProfile, method: java.lang.reflect.Method?, device: BluetoothDevice, label: String) {
        if (method == null) { appendLine("CONNECT: $label state unavailable"); return }
        try { method.isAccessible = true; appendLine("CONNECT: $label state=${method.invoke(proxy, device)}") }
        catch (t: Throwable) { appendLine("CONNECT: $label state exception=${t.javaClass.name}: ${t.message}") }
    }

    private fun classProbe(name: String): String = try { Class.forName(name); "PRESENT" } catch (t: Throwable) { "NOT PRESENT (${t.javaClass.simpleName})" }
    private fun appendLine(s: String) { runOnUiThread { report.append("\n$s") } }

    private fun getLocalUuids(adapter: BluetoothAdapter): List<String> = try {
        val m = BluetoothAdapter::class.java.getDeclaredMethod("getUuids"); m.isAccessible = true
        val result = m.invoke(adapter) as? Array<*>
        result?.mapNotNull { r -> try { r?.javaClass?.getField("uuid")?.get(r)?.toString() } catch (_: Throwable) { r?.toString() } } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    private fun classify(values: List<String>): String {
        val s = values.map { it.lowercase(Locale.US) }; val r = mutableListOf<String>()
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
        try { if (clientProfileId >= 0) headsetClientProxy?.let { adapter?.closeProfileProxy(clientProfileId, it) } } catch (_: Throwable) { }
    }
}
