package com.dsann.phonebridge.hfpdiagnostic

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var report: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var headsetClientProxy: BluetoothProfile? = null
    private var proxyListener: BluetoothProfile.ServiceListener? = null
    private var selectedDevice: BluetoothDevice? = null
    private var clientProfileId = -1
    private val permissionRequestCode = 7001

    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        report = findViewById(R.id.report)
        findViewById<Button>(R.id.run).setOnClickListener { startDiagnosticWithPermissionCheck() }
        findViewById<Button>(R.id.connect).setOnClickListener { connectHfpClient() }
        startDiagnosticWithPermissionCheck()
    }

    private fun bluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else emptyArray()
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return bluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startDiagnosticWithPermissionCheck() {
        if (!hasBluetoothPermissions()) {
            report.text = "PhoneBridge HFP Client Probe v3\n\nBluetooth permissions are required.\nRequesting BLUETOOTH_CONNECT and BLUETOOTH_SCAN..."
            requestPermissions(bluetoothPermissions(), permissionRequestCode)
            return
        }
        runDiagnostic()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permissionRequestCode) return
        if (hasBluetoothPermissions()) runDiagnostic()
        else report.text = "PhoneBridge HFP Client Probe v3\n\nBluetooth permissions: DENIED\nPlease allow Nearby devices permission in Android Settings and press RUN again."
    }

    private fun runDiagnostic() {
        val b = adapter ?: run { report.text = "PhoneBridge HFP Client Probe v3\nBluetooth adapter: NOT PRESENT"; return }
        val out = StringBuilder()
        out.appendLine("PhoneBridge HFP Client Probe v3")
        out.appendLine("Reason: Manual test")
        out.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        out.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        out.appendLine()
        out.appendLine("Bluetooth permissions: GRANTED")
        out.appendLine("Bluetooth adapter: PRESENT")
        out.appendLine("Enabled: ${b.isEnabled}")
        out.appendLine("Name: ${safeName(b)}")
        out.appendLine()
        out.appendLine("Paired devices")
        try {
            val devices = b.bondedDevices
            if (devices.isEmpty()) out.appendLine("  none")
            else devices.forEach { out.appendLine("  ${safeDeviceName(it)} / ${safeAddress(it)} / bond=${it.bondState}") }
        } catch (t: Throwable) { out.appendLine("  unavailable: ${t.javaClass.simpleName}: ${t.message}") }
        out.appendLine()
        out.appendLine("HFP Client framework")
        clientProfileId = try { BluetoothProfile::class.java.getField("HEADSET_CLIENT").getInt(null) } catch (_: Throwable) { -1 }
        out.appendLine("profile ID: ${if (clientProfileId >= 0) clientProfileId else "not available"}")
        try { Class.forName("android.bluetooth.BluetoothHeadsetClient"); out.appendLine("BluetoothHeadsetClient class: PRESENT") }
        catch (t: Throwable) { out.appendLine("BluetoothHeadsetClient class: NOT PRESENT (${t.javaClass.simpleName})") }
        out.appendLine()
        out.appendLine("Current proxy: ${if (headsetClientProxy != null) "READY" else "NOT CONNECTED"}")
        out.appendLine("Requesting HFP Client proxy...")
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
        report.text = out.toString()
    }

    private fun requestHfpClientProxy(profileId: Int) {
        try {
            proxyListener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != profileId) return
                    headsetClientProxy = proxy
                    appendLine("PROXY CALLBACK: CONNECTED / READY")
                    appendLine("PROXY class: ${proxy.javaClass.name}")
                    appendLine("connect(BluetoothDevice): ${if (findConnectMethod(proxy) != null) "FOUND" else "NOT FOUND"}")
                    appendLine("getConnectionState(BluetoothDevice): ${if (findStateMethod(proxy) != null) "FOUND" else "NOT FOUND"}")
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
        if (!hasBluetoothPermissions()) { startDiagnosticWithPermissionCheck(); return }
        val b = adapter ?: return
        val target = try { b.bondedDevices.firstOrNull { safeDeviceName(it).contains("PHAB", true) || safeDeviceName(it).contains("Lenovo", true) } } catch (t: Throwable) { appendLine("CONNECT TEST: paired-device access failed: ${t.message}"); null }
        if (target == null) { appendLine("CONNECT TEST: Lenovo PHAB2 Plus not found among bonded devices"); return }
        selectedDevice = target
        appendLine("CONNECT TEST: target=${safeDeviceName(target)} / ${safeAddress(target)}")
        if (headsetClientProxy != null) performConnectionProbe(target)
        else if (clientProfileId >= 0) { appendLine("CONNECT TEST: proxy not ready; requesting it now"); requestHfpClientProxy(clientProfileId) }
        else appendLine("CONNECT TEST: HEADSET_CLIENT unavailable")
    }

    private fun performConnectionProbe(device: BluetoothDevice) {
        val proxy = headsetClientProxy ?: run { appendLine("CONNECT: proxy still not ready"); return }
        val connectMethod = findConnectMethod(proxy)
        val stateMethod = findStateMethod(proxy)
        appendLine("CONNECT: proxy READY; beginning method probe")
        appendLine("CONNECT: connect(BluetoothDevice) method=${if (connectMethod != null) "FOUND" else "NOT FOUND"}")
        appendLine("CONNECT: getConnectionState(BluetoothDevice)=${if (stateMethod != null) "FOUND" else "NOT FOUND"}")
        try {
            connectMethod?.isAccessible = true
            appendLine("CONNECT: connect() result=${connectMethod?.invoke(proxy, device)}")
        } catch (t: Throwable) {
            appendLine("CONNECT: connect() exception=${t.javaClass.name}: ${t.message}")
            t.cause?.let { appendLine("CONNECT: cause=${it.javaClass.name}: ${it.message}") }
        }
        readState(proxy, stateMethod, device, "immediate")
        handler.postDelayed({ readState(proxy, stateMethod, device, "2s") }, 2000)
        handler.postDelayed({ readState(proxy, stateMethod, device, "5s") }, 5000)
    }

    private fun findConnectMethod(proxy: BluetoothProfile) = proxy.javaClass.methods.firstOrNull { it.name == "connect" && it.parameterTypes.size == 1 && BluetoothDevice::class.java.isAssignableFrom(it.parameterTypes[0]) }
    private fun findStateMethod(proxy: BluetoothProfile) = proxy.javaClass.methods.firstOrNull { it.name == "getConnectionState" && it.parameterTypes.size == 1 && BluetoothDevice::class.java.isAssignableFrom(it.parameterTypes[0]) }
    private fun readState(proxy: BluetoothProfile, method: java.lang.reflect.Method?, device: BluetoothDevice, label: String) {
        if (method == null) { appendLine("CONNECT: $label state unavailable"); return }
        try { method.isAccessible = true; appendLine("CONNECT: $label state=${method.invoke(proxy, device)}") }
        catch (t: Throwable) { appendLine("CONNECT: $label state exception=${t.javaClass.name}: ${t.message}") }
    }
    private fun classProbe(name: String): String = try { Class.forName(name); "PRESENT" } catch (t: Throwable) { "NOT PRESENT (${t.javaClass.simpleName})" }
    private fun appendLine(s: String) { runOnUiThread { report.append("\n$s") } }
    private fun safeName(b: BluetoothAdapter): String = try { b.name ?: "unknown" } catch (_: SecurityException) { "permission denied" }
    private fun safeDeviceName(d: BluetoothDevice): String = try { d.name ?: "unknown" } catch (_: SecurityException) { "permission denied" }
    private fun safeAddress(d: BluetoothDevice): String = try { d.address } catch (_: SecurityException) { "permission denied" }
    override fun onDestroy() { try { if (clientProfileId >= 0) headsetClientProxy?.let { adapter?.closeProfileProxy(clientProfileId, it) } } catch (_: Throwable) { }; super.onDestroy() }
}
