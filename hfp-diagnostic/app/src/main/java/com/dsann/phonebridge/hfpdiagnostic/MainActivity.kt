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
    private var selectedDevice: BluetoothDevice? = null
    private var clientProfileId = -1
    private val permissionRequestCode = 7001
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        report = findViewById(R.id.report)
        findViewById<Button>(R.id.run).setOnClickListener { startDiagnostic() }
        findViewById<Button>(R.id.connect).setOnClickListener { probeHfpClient() }
        startDiagnostic()
    }

    private fun perms() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray()
    private fun hasPerms() = Build.VERSION.SDK_INT < 31 || perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startDiagnostic() {
        if (!hasPerms()) {
            report.text = "PhoneBridge HFP Client Probe v4\n\nRequesting Nearby devices permission..."
            requestPermissions(perms(), permissionRequestCode)
            return
        }
        runDiagnostic()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (hasPerms()) runDiagnostic() else report.text = "PhoneBridge HFP Client Probe v4\n\nBluetooth permissions: DENIED"
        }
    }

    private fun runDiagnostic() {
        val b = adapter ?: run { report.text = "PhoneBridge HFP Client Probe v4\nBluetooth adapter: NOT PRESENT"; return }
        val s = StringBuilder()
        s.appendLine("PhoneBridge HFP Client Probe v4")
        s.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        s.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        s.appendLine("Bluetooth permissions: GRANTED")
        s.appendLine("Bluetooth adapter: PRESENT")
        s.appendLine("Enabled: ${b.isEnabled}")
        s.appendLine("Name: ${try { b.name } catch (_: Throwable) { "permission denied" }}")
        s.appendLine()
        s.appendLine("Paired devices")
        try { b.bondedDevices.forEach { s.appendLine("  ${it.name} / ${it.address} / bond=${it.bondState}") } }
        catch (t: Throwable) { s.appendLine("  unavailable: ${t.javaClass.simpleName}: ${t.message}") }
        s.appendLine()
        s.appendLine("PROFILE CONSTANTS")
        val ids = listOf("HEADSET" to BluetoothProfile.HEADSET, "A2DP" to BluetoothProfile.A2DP, "HEADSET_CLIENT" to getClientId())
        clientProfileId = getClientId()
        ids.forEach { (name, id) -> s.appendLine("$name = $id") }
        s.appendLine()
        s.appendLine("PROFILE PROXY REQUESTS")
        report.text = s.toString()
        ids.forEach { (name, id) -> requestProfile(name, id) }
    }

    private fun getClientId(): Int = try { BluetoothProfile::class.java.getField("HEADSET_CLIENT").getInt(null) } catch (_: Throwable) { -1 }

    private fun requestProfile(name: String, id: Int) {
        if (id < 0) { appendLine("$name: unavailable") ; return }
        try {
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    appendLine("$name: CALLBACK CONNECTED; profile=$profile; proxy=${proxy.javaClass.name}")
                    appendLine("$name: connect(BluetoothDevice)=${if (findMethod(proxy, "connect") != null) "FOUND" else "NOT FOUND"}")
                    appendLine("$name: getConnectionState(BluetoothDevice)=${if (findMethod(proxy, "getConnectionState") != null) "FOUND" else "NOT FOUND"}")
                }
                override fun onServiceDisconnected(profile: Int) { appendLine("$name: CALLBACK DISCONNECTED; profile=$profile") }
            }
            appendLine("$name: getProfileProxy=${adapter?.getProfileProxy(this, listener, id)}")
        } catch (t: Throwable) { appendLine("$name: exception=${t.javaClass.name}: ${t.message}") }
    }

    private fun probeHfpClient() {
        if (!hasPerms()) { startDiagnostic(); return }
        val b = adapter ?: return
        selectedDevice = try { b.bondedDevices.firstOrNull { it.name?.contains("PHAB", true) == true || it.name?.contains("Lenovo", true) == true } } catch (_: Throwable) { null }
        if (selectedDevice == null) { appendLine("TARGET: Lenovo PHAB2 Plus not found"); return }
        appendLine("TARGET: ${selectedDevice!!.name} / ${selectedDevice!!.address}")
        appendLine("HFP CLIENT: waiting for/observing callback above; press RUN again if needed")
    }

    private fun findMethod(proxy: BluetoothProfile, name: String) = proxy.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 && BluetoothDevice::class.java.isAssignableFrom(it.parameterTypes[0]) }
    private fun appendLine(x: String) { runOnUiThread { report.append("\n$x") } }
    override fun onDestroy() { super.onDestroy() }
}
