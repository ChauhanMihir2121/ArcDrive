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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

class BtBridge(private val activity: Activity, private val webView: WebView) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    
    private var btSocket: BluetoothSocket? = null
    private var readerThread: Thread? = null
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
                        val name = it.name ?: ""
                        // Only show ArcDrive devices in scan results
                        if (name.startsWith("ArcDrive") || name.startsWith("Arc")) {
                            sendDeviceToJs(name, it.address, false)
                        }
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

        // Only show ArcDrive devices
        bluetoothAdapter?.bondedDevices?.forEach { device ->
            val name = device.name ?: ""
            if (name.startsWith("ArcDrive") || name.startsWith("Arc")) {
                sendDeviceToJs(name, device.address, true)
            }
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
                // Short delay to let Bluetooth radio finish cancelling discovery
                try { Thread.sleep(150) } catch (_: Exception) {}

                val device = bluetoothAdapter?.getRemoteDevice(mac)
                    ?: throw IOException("Device not found: $mac")

                var socket: BluetoothSocket? = null
                var lastEx: Exception? = null

                // Method 1: Insecure RFCOMM by UUID (recommended for ESP32 BluetoothSerial)
                try {
                    socket = device.createInsecureRfcommSocketToServiceRecord(ArcProtocol.SPP_UUID)
                    socket.connect()
                } catch (e1: Exception) {
                    lastEx = e1
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                }

                // Method 2: Secure RFCOMM by UUID
                if (socket == null) {
                    try {
                        socket = device.createRfcommSocketToServiceRecord(ArcProtocol.SPP_UUID)
                        socket.connect()
                    } catch (e2: Exception) {
                        lastEx = e2
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    }
                }

                // Method 3: Direct channel 1 insecure reflection (bypasses SDP discovery failures on ESP32)
                if (socket == null) {
                    try {
                        val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                        val s = m.invoke(device, 1) as? BluetoothSocket
                        s?.connect()
                        socket = s
                    } catch (e3: Exception) {
                        lastEx = e3
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    }
                }

                // Method 4: Direct channel 1 secure reflection
                if (socket == null) {
                    try {
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        val s = m.invoke(device, 1) as? BluetoothSocket
                        s?.connect()
                        socket = s
                    } catch (e4: Exception) {
                        lastEx = e4
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    }
                }

                if (socket == null) {
                    throw lastEx ?: IOException("Unable to establish RFCOMM link")
                }

                btSocket = socket
                isConnected = true
                val name = device.name ?: mac
                
                activity.runOnUiThread {
                    runJs("if(window.ArcBT && window.ArcBT.onConnected) window.ArcBT.onConnected('${mac}', '${JSONObject.quote(name).drop(1).dropLast(1)}');")
                }

                // Run stream reader on its own dedicated thread so executor is FREE to send commands!
                readerThread?.interrupt()
                readerThread = Thread({
                    startReading(btSocket?.inputStream)
                }, "ArcBtReaderThread").apply { start() }
            } catch (e: Exception) {
                isConnected = false
                closeSocketInternal()
                val safeMsg = (e.message ?: "Failed").replace("'", "").take(45)
                activity.runOnUiThread {
                    runJs("if(window.ArcBT && window.ArcBT.onConnectFailed) window.ArcBT.onConnectFailed('$safeMsg');")
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

                    // Need at least 4 bytes to even inspect a header
                    if (streamBuffer.size < 4) {
                        break
                    }

                    val header = streamBuffer[0].toInt() and 0xFF
                    val type = streamBuffer[1].toInt() and 0xFF

                    // Priority: Check if we have a valid 5-byte frame (AA TYPE HI LO CHK5)
                    if (streamBuffer.size >= 5) {
                        val hi = streamBuffer[2].toInt() and 0xFF
                        val lo = streamBuffer[3].toInt() and 0xFF
                        val chk5 = streamBuffer[4].toInt() and 0xFF
                        if ((type xor hi xor lo) == chk5) {
                            val value = (hi shl 8) or lo
                            val hexStr = String.format("AA %02X %02X %02X %02X", type, hi, lo, chk5)
                            handleValidFrame(type, value, hexStr)
                            for (k in 0 until 5) {
                                streamBuffer.removeAt(0)
                            }
                            continue
                        }
                    }

                    // Fallback: Check if we have a valid 4-byte frame (AA TYPE VAL CHK4)
                    val value4 = streamBuffer[2].toInt() and 0xFF
                    val chk4 = streamBuffer[3].toInt() and 0xFF
                    if ((type xor value4) == chk4) {
                        val hexStr = String.format("AA %02X %02X %02X", type, value4, chk4)
                        handleValidFrame(type, value4, hexStr)
                        for (k in 0 until 4) {
                            streamBuffer.removeAt(0)
                        }
                        continue
                    }

                    // If neither 5-byte nor 4-byte matched, drop first 0xAA byte and continue searching
                    streamBuffer.removeAt(0)
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
            if (type == ArcProtocol.FB_START_ACK) {
                cancelAckTimeout()
                runJs("if(window.ArcBT && window.ArcBT.onStartAck) window.ArcBT.onStartAck($value);")
            } else if (type == ArcProtocol.FB_STOP_ACK && value == 0x00) {
                cancelAckTimeout()
                runJs("if(window.ArcBT && window.ArcBT.onStopAck) window.ArcBT.onStopAck();")
            } else if (type == ArcProtocol.FB_SPEED_ACK) {
                cancelAckTimeout()
                runJs("if(window.ArcBT && window.ArcBT.onSpeedAck) window.ArcBT.onSpeedAck($value);")
            } else if (type == ArcProtocol.FB_DIR_ACK) {
                cancelAckTimeout()
                runJs("if(window.ArcBT && window.ArcBT.onDirAck) window.ArcBT.onDirAck($value);")
            } else if (type == ArcProtocol.FB_ERROR) {
                cancelAckTimeout()
                runJs("if(window.ArcBT && window.ArcBT.onErrorFrame) window.ArcBT.onErrorFrame($value);")
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
    fun sendStart(speedRpm: Int, direction: Int) {
        if (!isConnected) {
            logHex("[ERROR] Not connected to ArcDrive")
            return
        }
        val clamped = speedRpm.coerceIn(0, 500)
        val hi = ((clamped shr 8) and 0xFF).toByte()
        val lo = (clamped and 0xFF).toByte()
        val dir = (if (direction == 1) 1 else 0).toByte()
        executor.execute {
            try {
                val out = btSocket?.outputStream ?: return@execute
                val packet = byteArrayOf(ArcProtocol.CMD_START, hi, lo, dir)
                out.write(packet)
                out.flush()
                activity.runOnUiThread {
                    logHex(String.format("TX: 01 %02X %02X %02X (Start @ %d RPM, %s)",
                        hi, lo, dir, clamped, if (dir.toInt() == 0) "FWD" else "REV"))
                    startAckTimeout()
                }
            } catch (e: Exception) {
                handleIoException()
            }
        }
    }

    @JavascriptInterface
    fun sendStart(speedRpm: Int) {
        sendStart(speedRpm, 0)
    }

    @JavascriptInterface
    fun sendStart() {
        sendStart(1, 0)
    }

    @JavascriptInterface
    fun sendDirection(direction: Int) {
        if (!isConnected) return
        val cmd = if (direction == 1) ArcProtocol.CMD_REVERSE else ArcProtocol.CMD_FORWARD
        executor.execute {
            try {
                val out = btSocket?.outputStream ?: return@execute
                out.write(byteArrayOf(cmd))
                out.flush()
                activity.runOnUiThread {
                    logHex(String.format("TX: %02X (%s)", cmd, if (direction == 1) "REVERSE" else "FORWARD"))
                    startAckTimeout()
                }
            } catch (e: Exception) {
                handleIoException()
            }
        }
    }

    @JavascriptInterface
    fun sendSpeed(speedRpm: Int) {
        if (!isConnected) {
            logHex("[ERROR] Not connected to ArcDrive")
            return
        }
        val clamped = speedRpm.coerceIn(0, 500)
        val hi = ((clamped shr 8) and 0xFF).toByte()
        val lo = (clamped and 0xFF).toByte()
        executor.execute {
            try {
                val out = btSocket?.outputStream ?: return@execute
                val packet = byteArrayOf(ArcProtocol.CMD_SET_SPEED, hi, lo)
                out.write(packet)
                out.flush()
                activity.runOnUiThread {
                    logHex(String.format("TX: 03 %02X %02X (Set Speed: %d RPM)", hi, lo, clamped))
                    startAckTimeout()
                }
            } catch (e: Exception) {
                handleIoException()
            }
        }
    }

    @JavascriptInterface
    fun sendStop() {
        if (!isConnected) {
            logHex("[ERROR] Not connected to ArcDrive")
            return
        }
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
        try {
            readerThread?.interrupt()
        } catch (e: Exception) {}
        readerThread = null
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

    @JavascriptInterface
    fun clearSavedMac() {
        prefs.edit().remove("saved_mac").apply()
    }

    /** Unpair / forget a bonded Bluetooth device by MAC address.
     *  Uses reflection as there is no public API for this on Android. */
    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun unpairDevice(mac: String) {
        try {
            val device = bluetoothAdapter?.getRemoteDevice(mac) ?: return
            val m = device.javaClass.getMethod("removeBond")
            m.invoke(device)
            // Also clear saved mac if it matches
            if (prefs.getString("saved_mac", null) == mac) clearSavedMac()
            activity.runOnUiThread {
                runJs("if(window.ArcBT && window.ArcBT.onUnpaired) window.ArcBT.onUnpaired('$mac');")
            }
        } catch (e: Exception) {
            activity.runOnUiThread {
                runJs("if(window.ArcBT && window.ArcBT.onConnectFailed) window.ArcBT.onConnectFailed('unpair_failed');")
            }
        }
    }
}
