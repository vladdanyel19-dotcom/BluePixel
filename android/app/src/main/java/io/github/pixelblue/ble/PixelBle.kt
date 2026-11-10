package io.github.pixelblue.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

data class FoundDevice(val address: String, val name: String, val rssi: Int)

enum class ConnState { Disconnected, Connecting, Connected }

/**
 * Bluetooth LE link to a PixelBlue controller.
 *
 * Android allows one GATT operation at a time, so every operation goes through
 * a single worker coroutine. Fast-changing values (sliders, color wheel) are
 * coalesced by key: only the newest value is sent.
 */
@SuppressLint("MissingPermission") // permissions are checked by MainActivity before use
class PixelBle(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("pixelblue", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()

    private val _conn = MutableStateFlow(ConnState.Disconnected)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _state = MutableStateFlow<DeviceState?>(null)
    val state: StateFlow<DeviceState?> = _state.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    var connectedName: String = ""
        private set

    /** Updates the shown name after renaming the controller. */
    fun rememberName(n: String) {
        connectedName = n
        prefs.edit().putString("lastName", n).apply()
    }

    val lastAddress: String? get() = prefs.getString("last", null)
    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var pixChar: BluetoothGattCharacteristic? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var wantConnected: String? = null
    private var mtu = 23

    // ------------------------------------------------------------ operation queue
    private sealed class Op {
        data class Write(val key: String?, val data: ByteArray?, val pixels: Boolean) : Op()
        data object EnableNotify : Op()
        data object ReadState : Op()
        data object RequestMtu : Op()
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val latest = ConcurrentHashMap<String, ByteArray>()
    private var pending: CompletableDeferred<Boolean>? = null
    private var worker: Job? = null

    private fun startWorker() {
        worker?.cancel()
        worker = scope.launch {
            for (op in ops) {
                val g = gatt ?: continue
                when (op) {
                    is Op.RequestMtu -> run { if (!g.requestMtu(247)) return@run; awaitOp(1500) }
                    is Op.EnableNotify -> enableNotify(g)
                    is Op.ReadState -> stateChar?.let { if (g.readCharacteristic(it)) awaitOp() }
                    is Op.Write -> {
                        val data = op.key?.let { latest.remove(it) } ?: op.data ?: continue
                        val ch = (if (op.pixels) pixChar else cmdChar) ?: continue
                        val type = if (op.pixels) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        if (writeChar(g, ch, data, type)) awaitOp()
                    }
                }
            }
        }
    }

    private suspend fun awaitOp(timeoutMs: Long = 2000): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = d
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: false
    }

    private fun completeOp(ok: Boolean) {
        pending?.complete(ok)
        pending = null
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, v: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, v, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = v
            g.writeCharacteristic(ch)
        }

    @Suppress("DEPRECATION")
    private suspend fun enableNotify(g: BluetoothGatt) {
        val ch = stateChar ?: return
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(Protocol.CCCD) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
        if (ok) awaitOp()
    }

    // ------------------------------------------------------------ public API
    /** Send a command. With a [key], rapid repeats collapse into the newest value. */
    fun send(data: ByteArray, key: String? = null) {
        if (_conn.value != ConnState.Connected) return
        if (key != null) {
            val first = latest.put(key, data) == null
            if (first) ops.trySend(Op.Write(key, null, false))
        } else {
            ops.trySend(Op.Write(null, data, false))
        }
    }

    /** Upload RGB data for logical pixels starting at [offset], split to fit the MTU. */
    fun sendPixels(offset: Int, rgb: ByteArray) {
        if (_conn.value != ConnState.Connected) return
        val maxPayload = ((mtu - 3 - 2) / 3) * 3
        var pos = 0
        while (pos < rgb.size) {
            val end = minOf(rgb.size, pos + maxPayload)
            ops.trySend(Op.Write(null, Protocol.pixels(offset + pos / 3, rgb.copyOfRange(pos, end)), true))
            pos = end
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        _devices.value = emptyList()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            _scanning.value = true
        } catch (_: Exception) {
        }
        scope.launch {
            delay(20_000)
            stopScan()
        }
    }

    fun stopScan() {
        if (!_scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        _scanning.value = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "PixelBlue"
            val found = FoundDevice(result.device.address, name, result.rssi)
            _devices.value = (_devices.value.filter { it.address != found.address } + found)
                .sortedByDescending { it.rssi }
            // Auto-connect to the controller used last time, as soon as it shows up.
            if (found.address == lastAddress && _conn.value == ConnState.Disconnected && wantConnected == null) {
                connect(found.address, found.name)
            }
        }
    }

    fun connect(address: String, name: String = "") {
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return
        stopScan()
        closeGatt()
        wantConnected = address
        connectedName = name.ifBlank { device.name ?: "PixelBlue" }
        prefs.edit().putString("last", address).putString("lastName", connectedName).apply()
        _conn.value = ConnState.Connecting
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect(forget: Boolean = false) {
        wantConnected = null
        if (forget) prefs.edit().remove("last").apply()
        closeGatt()
        _conn.value = ConnState.Disconnected
        _state.value = null
    }

    private fun closeGatt() {
        worker?.cancel()
        latest.clear()
        while (ops.tryReceive().isSuccess) { /* drain */ }
        completeOp(false)
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        cmdChar = null; pixChar = null; stateChar = null
        mtu = 23
    }

    private fun scheduleReconnect() {
        val addr = wantConnected ?: return
        scope.launch {
            delay(1500)
            if (wantConnected == addr && _conn.value != ConnState.Connected) connect(addr, connectedName)
        }
    }

    // ------------------------------------------------------------ GATT callbacks
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (g != gatt) return@launch
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!g.discoverServices()) { closeGatt(); _conn.value = ConnState.Disconnected; scheduleReconnect() }
                } else {
                    closeGatt()
                    _conn.value = ConnState.Disconnected
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                if (g != gatt) return@launch
                val svc = g.getService(Protocol.SERVICE)
                if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                    disconnect()
                    return@launch
                }
                cmdChar = svc.getCharacteristic(Protocol.CMD)
                pixChar = svc.getCharacteristic(Protocol.PIXELS)
                stateChar = svc.getCharacteristic(Protocol.STATE)
                startWorker()
                ops.trySend(Op.RequestMtu)
                ops.trySend(Op.EnableNotify)
                ops.trySend(Op.ReadState)
                _conn.value = ConnState.Connected
                // Give the controller the phone's clock so schedules run on their own.
                val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
                send(Protocol.time(System.currentTimeMillis() / 1000, tz))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                completeOp(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            scope.launch { completeOp(status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            scope.launch { completeOp(status == BluetoothGatt.GATT_SUCCESS) }
        }

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) onState(value)
                completeOp(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value ?: ByteArray(0)
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) onState(v)
                completeOp(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == Protocol.STATE) scope.launch { onState(value) }
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            if (c.uuid == Protocol.STATE) scope.launch { onState(v) }
        }
    }

    private fun onState(bytes: ByteArray) {
        Protocol.parseState(bytes)?.let { _state.value = it }
    }
}
