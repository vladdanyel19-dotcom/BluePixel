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

data class FoundDevice(val address: String, val name: String, val rssi: Int, val kind: Kind)

enum class ConnState { Disconnected, Connecting, Connected }

/**
 * Bluetooth LE link to a PixelBlue controller or an original SP110E controller.
 *
 * Android allows one GATT operation at a time, so every operation goes through
 * a single worker coroutine. Fast-changing values (sliders, color wheel) are
 * coalesced by key: only the newest value is sent.
 *
 * The UI always speaks the PixelBlue command format; for an SP110E the commands
 * are translated here, and in demo mode they only update the local state.
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

    var isDemo = false
        private set

    private var kind = Kind.PIXELBLUE

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
    private var auxChar: BluetoothGattCharacteristic? = null
    private var wantConnected: String? = null
    private var autoConnect = true   // off after the user leaves a controller on purpose
    private var mtu = 23

    // ------------------------------------------------------------ operation queue
    private enum class Target { CMD, PIXELS, AUX }

    private sealed class Op {
        data class Write(val key: String?, val data: ByteArray?, val target: Target) : Op()
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
                        val ch = when (op.target) {
                            Target.CMD -> cmdChar
                            Target.PIXELS -> pixChar
                            Target.AUX -> auxChar
                        } ?: continue
                        val canAck = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                        val type = if (op.target == Target.PIXELS || !canAck)
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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

    private fun queueWrite(data: ByteArray, key: String?, target: Target = Target.CMD) {
        if (key != null) {
            val first = latest.put(key, data) == null
            if (first) ops.trySend(Op.Write(key, null, target))
        } else {
            ops.trySend(Op.Write(null, data, target))
        }
    }

    // ------------------------------------------------------------ public API
    /** Send a command. With a [key], rapid repeats collapse into the newest value. */
    fun send(data: ByteArray, key: String? = null) {
        if (_conn.value != ConnState.Connected) return
        if (isDemo) {
            _state.value = _state.value?.apply(data)
            return
        }
        if (kind == Kind.SP110E) {
            val packets = Sp110e.translate(data)
            if (packets.isEmpty()) return
            // The SP110E does not report changes by itself: show them right away.
            _state.value = _state.value?.apply(data)
            packets.forEachIndexed { i, p -> queueWrite(p, key?.let { "$it#$i" }) }
            return
        }
        queueWrite(data, key)
    }

    /** Upload RGB data for logical pixels starting at [offset], split to fit the MTU. */
    fun sendPixels(offset: Int, rgb: ByteArray) {
        if (_conn.value != ConnState.Connected || isDemo || kind != Kind.PIXELBLUE) return
        val maxPayload = ((mtu - 3 - 2) / 3) * 3
        var pos = 0
        while (pos < rgb.size) {
            val end = minOf(rgb.size, pos + maxPayload)
            ops.trySend(Op.Write(null, Protocol.pixels(offset + pos / 3, rgb.copyOfRange(pos, end)), Target.PIXELS))
            pos = end
        }
    }

    /** Opens the full interface with a simulated 16x16 matrix, no hardware needed. */
    fun startDemo() {
        stopScan()
        closeGatt()
        wantConnected = null
        isDemo = true
        kind = Kind.PIXELBLUE
        connectedName = "Demo"
        _state.value = DeviceState.demo()
        _conn.value = ConnState.Connected
    }

    private fun kindOf(r: ScanResult): Kind? {
        val uuids = r.scanRecord?.serviceUuids.orEmpty()
        if (uuids.contains(ParcelUuid(Protocol.SERVICE))) return Kind.PIXELBLUE
        val name = r.scanRecord?.deviceName ?: r.device.name ?: return null
        // SP105E / SP110E and their siblings advertise names like "SP110E" or "SP110E_xxxx".
        if (Regex("^SP1\\d{2}E", RegexOption.IGNORE_CASE).containsMatchIn(name)) return Kind.SP110E
        return null
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        _devices.value = emptyList()
        // No hardware filter: SP110E controllers don't advertise a service UUID,
        // so results are filtered in kindOf().
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
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
            val k = kindOf(result) ?: return
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "PixelBlue"
            val found = FoundDevice(result.device.address, name, result.rssi, k)
            _devices.value = (_devices.value.filter { it.address != found.address } + found)
                .sortedByDescending { it.rssi }
            // Auto-connect to the controller used last time, as soon as it shows up.
            if (found.address == lastAddress && _conn.value == ConnState.Disconnected && wantConnected == null && autoConnect) {
                connect(found.address, found.name)
            }
        }
    }

    fun connect(address: String, name: String = "") {
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return
        stopScan()
        closeGatt()
        isDemo = false
        autoConnect = true
        wantConnected = address
        connectedName = name.ifBlank { device.name ?: "PixelBlue" }
        prefs.edit().putString("last", address).putString("lastName", connectedName).apply()
        _conn.value = ConnState.Connecting
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect(forget: Boolean = false) {
        wantConnected = null
        autoConnect = false
        if (forget) prefs.edit().remove("last").apply()
        isDemo = false
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
        cmdChar = null; pixChar = null; stateChar = null; auxChar = null
        mtu = 23
    }

    private fun scheduleReconnect() {
        val addr = wantConnected ?: return
        scope.launch {
            delay(1500)
            if (wantConnected == addr && _conn.value != ConnState.Connected) connect(addr, connectedName)
        }
    }

    private fun setupPixelBlue(g: BluetoothGatt) {
        val svc = g.getService(Protocol.SERVICE) ?: return
        kind = Kind.PIXELBLUE
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

    private fun setupSp110e(g: BluetoothGatt) {
        val svc = g.getService(Sp110e.SERVICE) ?: return
        kind = Kind.SP110E
        cmdChar = svc.getCharacteristic(Sp110e.CHAR)
        stateChar = cmdChar
        auxChar = svc.getCharacteristic(Sp110e.AUX)
        startWorker()
        ops.trySend(Op.EnableNotify)
        if (auxChar != null) queueWrite(byteArrayOf(0x01, 0x00), null, Target.AUX)
        queueWrite(Sp110e.CHECK, null)
        queueWrite(Sp110e.INFO, null)
        _state.value = Sp110e.default()
        _conn.value = ConnState.Connected
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
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> disconnect()
                    g.getService(Protocol.SERVICE) != null -> setupPixelBlue(g)
                    g.getService(Sp110e.SERVICE) != null -> setupSp110e(g)
                    else -> disconnect()
                }
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
            if (c.uuid == stateChar?.uuid) scope.launch { onState(value) }
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            if (c.uuid == stateChar?.uuid) scope.launch { onState(v) }
        }
    }

    private fun onState(bytes: ByteArray) {
        val parsed = if (kind == Kind.SP110E) Sp110e.parse(bytes, _state.value) else Protocol.parseState(bytes)
        parsed?.let { _state.value = it }
    }
}
