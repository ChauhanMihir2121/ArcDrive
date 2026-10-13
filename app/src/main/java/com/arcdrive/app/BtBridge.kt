package com.arcdrive.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

class BtBridge(private val activity: Activity, private val webView: WebView) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    
    private var btSocket: BluetoothSocket? = null
    @Volatile
    private var isConnected = false
    private val prefs: SharedPreferences = activity.getSharedPreferences("ArcDrivePrefs", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ackTimeoutRunnable: Runnable? = null

    private val scanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    if (hasPermissions()) {
                        val name = it.name ?: it.address
                        val mac = it.address
                        sendDeviceToJs(name, mac, false)
                    }
                }
            } else if (intent?.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                runJs("if(window.ArcBT && window.ArcBT.onScanFinished) window.ArcBT.onScanFinished();")
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        activity.registerReceiver(scanReceiver, filter)
    }

    private fun runJs(js: String) {
        activity.runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    private fun logHex(text: String) {
        activity.runOnUiThread {
            val quoted = JSONObject.quote(text)
            runJs("if(window.ArcBT && window.ArcBT.onLog) window.ArcBT.onLog($quoted);")
        }
    }

    @JavascriptInterface
    fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                101
            )
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                101
            )
        }
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun startScan() {
        if (!hasPermissions()) {
            requestPermissions()
            runJs("if(window.ArcBT && window.ArcBT.onConnectFailed) window.ArcBT.onConnectFailed('permission');")
            return
        }
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            activity.startActivityForResult(enableBtIntent, 102)
            runJs("if(window.ArcBT && window.ArcBT.onConnectFailed) window.ArcBT.onConnectFailed('disabled');")
            return
        }

        bluetoothAdapter?.bondedDevices?.forEach { device ->
            val name = device.name ?: device.address
            val mac = device.address
            sendDeviceToJs(name, mac, true)
        }

        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        bluetoothAdapter?.startDiscovery()
    }

    private fun sendDeviceToJs(name: String, mac: String, bonded: Boolean) {
        val json = JSONObject()
        json.put("name", name)
        json.put("mac", mac)
        json.put("bonded", bonded)
        val jsStr = JSONObject.quote(json.toString())
        runJs("if(window.ArcBT && window.ArcBT.onDevice) window.ArcBT.onDevice($jsStr);")
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun stopScan() {
        if (hasPermissions() && bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun connect(mac: String) {
        if (!hasPermissions()) {
            runJs("if(window.ArcBT && window.ArcBT.onConnectFailed) window.ArcBT.onConnectFailed('permission');")
            return
        }

        stopScan()
        
        executor.execute {
            // Close any previous open socket before reconnecting
            closeSocketInternal()

            try {
                if (hasPermissions() && bluetoothAdapter?.isDiscovering == true) {
                    bluetoothAdapter?.cancelDiscovery()
                }

                val device = bluetoothAdapter?.getRemoteDevice(mac)
                btSocket = device?.createRfcommSocketToServiceRecord(ArcProtocol.SPP_UUID)
                btSocket?.connect()
                isConnected = true
                val name = device?.name ?: mac
                
                activity.runOnUiThread {
                    runJs("if(window.ArcBT && window.ArcBT.onConnected) window.ArcBT.onConnected('${mac}', '${JSONObject.quote(name).drop(1).dropLast(1)}');")
                }

                startReading(btSocket?.inputStream)
            } catch (e: Exception) {
                isConnected = false
                closeSocketInternal()
                activity.runOnUiThread {
                    runJs("if(window.ArcBT && window.ArcBT.onConnectFailed) window.ArcBT.onConnectFailed('exception');")
                }
            }
        }
    }

    private fun startReading(inputStream: InputStream?) {
        inputStream ?: return
        val buffer = ByteArray(1024)
        val streamBuffer = ArrayList<Byte>()

        while (isConnected) {
            try {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) break

                for (i in 0 until bytesRead) {
                    streamBuffer.add(buffer[i])
                }

                // Process stream frames
                while (true) {
                    // Search for FRAME_HEADER (0xAA)
                    var headerIdx = -1
                    for (i in 0 until streamBuffer.size) {
                        if ((streamBuffer[i].toInt() and 0xFF) == ArcProtocol.FRAME_HEADER) {
                            headerIdx = i
                            break
                        }
                    }

                    if (headerIdx == -1) {
                        streamBuffer.clear()
                        break
                    }

                    // Drop any corrupted bytes preceding the 0xAA header
                    if (headerIdx > 0) {
                        for (k in 0 until headerIdx) {
                            streamBuffer.removeAt(0)
                        }
                    }

                    // Need at least 4 bytes for a full frame
                    if (streamBuffer.size < 4) {
                        break
                    }

                    val header = streamBuffer[0].toInt() and 0xFF
                    val type = streamBuffer[1].toInt() and 0xFF
                    val value = streamBuffer[2].toInt() and 0xFF
                    val checksum = streamBuffer[3].toInt() and 0xFF

                    if ((type xor value) == checksum) {
                        // Valid 4-byte feedback frame!
                        val hexStr = String.format("AA %02X %02X %02X", type, value, checksum)
                        handleValidFrame(type, value, hexStr)

                        for (k in 0 until 4) {
                            streamBuffer.removeAt(0)
                        }
                    } else {
                        // Invalid checksum: drop only first 0xAA byte and continue searching
                        streamBuffer.removeAt(0)
                    }
                }
            } catch (e: Exception) {
                break
            }
        }

        if (isConnected) {
            isConnected = false
            closeSocketInternal()
            cancelAckTimeout()
            activity.runOnUiThread {
                runJs("if(window.ArcBT && window.ArcBT.onDisconnected) window.ArcBT.onDisconnected();")
            }
        }
    }

    private fun handleValidFrame(type: Int, value: Int, hexStr: String) {
        activity.runOnUiThread {
            logHex("RX: $hexStr")
            if (type == ArcProtocol.FB_START_ACK && value == 0x01) {
                cancelAckTimeout()
                runJs("if(window.ArcBT && window.ArcBT.onStartAck) window.ArcBT.onStartAck();")
            } else if (type == ArcProtocol.FB_STOP_ACK && value == 0x00) {
                cancelAckTimeout()
                runJs("if(window.ArcBT && window.ArcBT.onStopAck) window.ArcBT.onStopAck();")
            } else if (type == ArcProtocol.FB_ERROR) {
                cancelAckTimeout()
                runJs("if(window.ArcBT && window.ArcBT.onErrorFrame) window.ArcBT.onErrorFrame();")
            }
        }
    }

    private fun startAckTimeout() {
        cancelAckTimeout()
        ackTimeoutRunnable = Runnable {
            runJs("if(window.ArcBT && window.ArcBT.onAckTimeout) window.ArcBT.onAckTimeout();")
        }
        mainHandler.postDelayed(ackTimeoutRunnable!!, ArcProtocol.ACK_TIMEOUT_MS)
    }

    private fun cancelAckTimeout() {
        ackTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            ackTimeoutRunnable = null
        }
    }

    @JavascriptInterface
    fun sendStart() {
        if (!isConnected) return
        executor.execute {
            try {
                val out = btSocket?.outputStream ?: return@execute
                out.write(byteArrayOf(ArcProtocol.CMD_START))
                out.flush()
                activity.runOnUiThread {
                    logHex("TX: 01")
                    startAckTimeout()
                }
            } catch (e: Exception) {
                handleIoException()
            }
        }
    }

    @JavascriptInterface
    fun sendStop() {
        if (!isConnected) return
        executor.execute {
            try {
                val out = btSocket?.outputStream ?: return@execute
                out.write(byteArrayOf(ArcProtocol.CMD_STOP))
                out.flush()
                activity.runOnUiThread {
                    logHex("TX: 02")
                }
            } catch (e: Exception) {
                handleIoException()
            }
        }
    }

    @JavascriptInterface
    fun sendByte(byteVal: Int) {
        if (!isConnected) return
        executor.execute {
            try {
                val out = btSocket?.outputStream ?: return@execute
                out.write(byteVal)
                out.flush()
                activity.runOnUiThread {
                    logHex(String.format("TX: %02X", byteVal and 0xFF))
                }
            } catch (e: Exception) {
                handleIoException()
            }
        }
    }

    @JavascriptInterface
    fun disconnect() {
        isConnected = false
        cancelAckTimeout()
        closeSocketInternal()
        activity.runOnUiThread {
            runJs("if(window.ArcBT && window.ArcBT.onDisconnected) window.ArcBT.onDisconnected();")
        }
    }

    private fun handleIoException() {
        isConnected = false
        cancelAckTimeout()
        closeSocketInternal()
        activity.runOnUiThread {
            runJs("if(window.ArcBT && window.ArcBT.onDisconnected) window.ArcBT.onDisconnected();")
        }
    }

    private fun closeSocketInternal() {
        try {
            btSocket?.close()
        } catch (e: Exception) {}
        btSocket = null
    }

    fun onAppBackground() {
        if (isConnected) {
            try {
                // Best-effort send STOP (0x02) to safely stop motor before closing socket
                btSocket?.outputStream?.write(byteArrayOf(ArcProtocol.CMD_STOP))
                btSocket?.outputStream?.flush()
            } catch (e: Exception) {}
            disconnect()
        }
    }

    @JavascriptInterface
    fun getSavedMac(): String? {
        return prefs.getString("saved_mac", null)
    }

    @JavascriptInterface
    fun saveMac(mac: String) {
        prefs.edit().putString("saved_mac", mac).apply()
    }
}
