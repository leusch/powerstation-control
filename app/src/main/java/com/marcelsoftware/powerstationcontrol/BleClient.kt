package com.marcelsoftware.powerstationcontrol

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Conn { Idle, Scanning, Connecting, Connected }

data class Found(val name: String, val address: String, val rssi: Int) {
    /** Names seen on this unit family (BrightEMS-compatible stations advertise as POWER-xx / FOSSIBOT-xx). */
    val likely: Boolean get() = listOf("POWER", "FOSSIBOT", "AFERIY", "SYDPOWER").any { name.startsWith(it, ignoreCase = true) }
}

@SuppressLint("MissingPermission") // permissions are requested in MainActivity before use
class BleClient(private val ctx: Context) {
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())

    val conn = MutableStateFlow(Conn.Idle)
    val devices = MutableStateFlow<List<Found>>(emptyList())
    val status = MutableStateFlow<Status?>(null)
    val settingsRegs = MutableStateFlow<List<Int>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())
    /** Human-readable outcome of the last write (confirmed by echo / rejected by the unit). */
    val lastWrite = MutableStateFlow<String?>(null)

    private val prefs = ctx.getSharedPreferences("powerstation", Context.MODE_PRIVATE)
    /** Developer options (raw registers, change log, mapping tools) are hidden unless switched on in the settings page. */
    val devMode = MutableStateFlow(prefs.getBoolean("dev", false))
    fun setDevMode(on: Boolean) { prefs.edit().putBoolean("dev", on).apply(); devMode.value = on }

    val savedAddress: String? get() = prefs.getString("addr", null)
    val savedName: String? get() = prefs.getString("name", null)

    fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= 31)
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private var manualDisconnect = false
    private var autoPick = false
    private var scanToken = 0
    private var scanRounds = 0
    private var pendingName: String? = null
    private var lastAddress: String? = null
    private var retries = 0
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var polling = false
    private var tick = 0

    /** Mapping aid: every register change and user note, timestamped, newest last. */
    val changes = MutableStateFlow<List<String>>(emptyList())
    private var prevStatus: List<Int>? = null
    private var prevSettings: List<Int>? = null
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun stamp() = clock.format(Date())
    private fun addChange(line: String) { Log.i("PowerstationControl", line); changes.value = (changes.value + line).takeLast(400) }

    fun note(text: String) { if (text.isNotBlank()) addChange("${stamp()}  NOTE: ${text.trim()}") }
    fun pollNow() { send(Protocol.readStatus()); main.postDelayed({ send(Protocol.readSettings()) }, 400) }
    fun clearChanges() { changes.value = emptyList() }

    private fun diff(kind: String, old: List<Int>?, new: List<Int>) {
        if (old == null) { addChange("${stamp()}  [$kind] first reading (${new.size} registers)"); return }
        for (i in new.indices) {
            val o = old.getOrNull(i) ?: 0
            if (o != new[i]) addChange("${stamp()}  $kind[$i]  $o -> ${new[i]}  (0x%04x -> 0x%04x)".format(o, new[i]))
        }
    }

    /** Plain-text report to paste back: notes + change log + full current register dump. */
    fun report(): String = buildString {
        appendLine("Powerstation Control mapping report ${stamp()}")
        appendLine("== change log =="); changes.value.forEach { appendLine(it) }
        appendLine("== status registers (index=value, non-zero) ==")
        appendLine(status.value?.registers?.withIndex()?.filter { it.value != 0 }?.joinToString { "${it.index}=${it.value}" } ?: "none")
        appendLine("== settings registers (index=value, non-zero) ==")
        appendLine(settingsRegs.value.withIndex().filter { it.value != 0 }.joinToString { "${it.index}=${it.value}" })
    }

    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private var sayToken = 0
    /** Short feedback message shown to the user; disappears by itself. */
    private fun say(msg: String, clearAfterMs: Long = 8000) {
        lastWrite.value = msg
        val t = ++sayToken
        main.postDelayed({ if (t == sayToken) lastWrite.value = null }, clearAfterMs)
    }

    private fun addLog(s: String) { Log.i("PowerstationControl", "log: $s"); log.value = (log.value + s).takeLast(60) }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: return
            val f = Found(name, r.device.address, r.rssi)
            devices.value = (devices.value.filter { it.address != f.address } + f).sortedByDescending { it.rssi }
            // First start with no remembered unit: connect to the first matching station automatically.
            if (autoPick && f.likely) { autoPick = false; main.post { connect(f.address, f.name) } }
        }
        override fun onScanFailed(code: Int) { error.value = str(R.string.search_failed, code); conn.value = Conn.Idle }
    }

    fun startScan() {
        val sc = adapter?.bluetoothLeScanner ?: run { error.value = str(R.string.bt_off); return }
        devices.value = emptyList(); error.value = null; conn.value = Conn.Scanning
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        val token = ++scanToken
        main.postDelayed({ if (token == scanToken) onScanTimeout() }, 12_000)
    }

    /** A scan round ended. During the automatic first-time search, try again a few times before giving up. */
    private fun onScanTimeout() {
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
        if (conn.value != Conn.Scanning) return
        if (autoPick && scanRounds < 3) { scanRounds++; startScan() } else conn.value = Conn.Idle
    }

    fun stopScan() {
        scanToken++ // cancels the pending scan timeout
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
        if (conn.value == Conn.Scanning) conn.value = Conn.Idle
    }

    /** Reconnect to the remembered unit, or start searching when there is none. */
    fun start() {
        if (conn.value != Conn.Idle) return
        manualDisconnect = false
        val a = savedAddress
        if (a != null) connect(a, savedName) else { autoPick = true; scanRounds = 0; startScan() }
    }

    /** Called when the app comes back to the foreground. */
    fun resume() {
        if (conn.value == Conn.Idle && !manualDisconnect && savedAddress != null && hasPermissions()) start()
    }

    /** Forget the remembered unit and search for another one. */
    fun forgetAndSearch() {
        disconnect(manual = true)
        prefs.edit().remove("addr").remove("name").apply()
        manualDisconnect = false
        autoPick = false
        startScan()
    }

    fun connect(address: String, name: String? = null) {
        stopScan()
        val dev = adapter?.getRemoteDevice(address) ?: run { error.value = str(R.string.bt_off); return }
        manualDisconnect = false
        pendingName = name ?: devices.value.firstOrNull { it.address == address }?.name
        lastAddress = address
        conn.value = Conn.Connecting; error.value = null
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect(manual: Boolean = false) {
        if (manual) manualDisconnect = true
        lastAddress = null
        polling = false
        txQueue.clear(); txBusy = false; rx = ByteArray(0)
        gatt?.disconnect(); gatt?.close(); gatt = null; writeChar = null
        conn.value = Conn.Idle; status.value = null; settingsRegs.value = emptyList()
        prevStatus = null; prevSettings = null
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, new: Int) {
            if (new == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices() // BrightEMS does not negotiate MTU; replies arrive in 20-byte pieces
            } else {
                main.post {
                    polling = false; g.close(); gatt = null
                    val addr = lastAddress
                    if (conn.value != Conn.Idle) addLog("disconnected, GATT status $st")
                    if (addr != null && retries < 5) {
                        retries++; conn.value = Conn.Connecting
                        error.value = str(R.string.reconnecting, retries)
                        main.postDelayed({ if (lastAddress == addr) connect(addr) }, 1500)
                    } else {
                        if (conn.value != Conn.Idle) error.value = str(R.string.connection_lost)
                        conn.value = Conn.Idle
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(Protocol.SERVICE)
            val w = svc?.getCharacteristic(Protocol.WRITE_CHAR)
            val n = svc?.getCharacteristic(Protocol.NOTIFY_CHAR)
            if (w == null || n == null) {
                val uuids = g.services.joinToString { it.uuid.toString().substring(4, 8) }
                main.post { error.value = str(R.string.incompatible, uuids); disconnect() }
                return
            }
            writeChar = w
            g.setCharacteristicNotification(n, true)
            val d = n.getDescriptor(Protocol.CCCD)
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post {
                Log.i("PowerstationControl", "CONNECTED"); retries = 0
                lastAddress?.let { prefs.edit().putString("addr", it).putString("name", pendingName ?: savedName).apply() }
                 error.value = null; conn.value = Conn.Connected; startPolling()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post { txBusy = false; pump() }
        }

        @Deprecated("API < 33 callback, still invoked on all versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            main.post { onChunk(v) }
        }
    }

    private var rx = ByteArray(0)

    /** Replies are longer than the 20-byte notification size, so reassemble by frame length. */
    private fun onChunk(chunk: ByteArray) {
        if (rx.isEmpty() && (chunk.isEmpty() || (chunk[0].toInt() and 0xFF) != Protocol.ADDR)) return
        rx += chunk
        val want = Protocol.expectedLength(rx) ?: return
        if (rx.size < want) return
        val frame = rx.copyOf(want); rx = ByteArray(0)
        val f = Protocol.toFrame(frame)
        if (!f.crcOk) { addLog("bad CRC, dropped fn ${f.function}"); return }
        Log.i("PowerstationControl", "rx fn=${f.function} len=${frame.size} ${stamp()}" +
            if (f.function != Protocol.FN_READ_STATUS && f.function != Protocol.FN_READ_SETTINGS)
                "  RAW " + frame.joinToString("") { "%02x".format(it) } else "")
        when (f.function) {
            Protocol.FN_READ_STATUS -> { diff("status", prevStatus, f.registers); prevStatus = f.registers; status.value = Status(f.registers) }
            Protocol.FN_READ_SETTINGS -> { diff("settings", prevSettings, f.registers); prevSettings = f.registers; settingsRegs.value = f.registers }
            0x85, 0x86 -> { say(str(R.string.cmd_rejected)); addLog("REJECTED ${frame.joinToString("") { "%02x".format(it) }}") }
            Protocol.FN_WRITE_STATE, 6 -> { addLog("write ack ${frame.joinToString("") { "%02x".format(it) }}") }
        }
    }

    /** GATT allows one write at a time, so commands are queued and sent one after the other. */
    private val txQueue = ArrayDeque<ByteArray>()
    private var txBusy = false
    private var txBusySince = 0L

    private fun send(bytes: ByteArray) { txQueue.addLast(bytes); pump() }

    private fun pump() {
        val g = gatt ?: return
        val c = writeChar ?: return
        if (txBusy && System.currentTimeMillis() - txBusySince > 3000) txBusy = false
        if (txBusy) return
        val next = txQueue.removeFirstOrNull() ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // BrightEMS uses write requests (0x12)
        c.value = next
        if (g.writeCharacteristic(c)) { txBusy = true; txBusySince = System.currentTimeMillis() }
        else { txQueue.addFirst(next); main.postDelayed({ pump() }, 150) }
    }

    private fun startPolling() {
        polling = true; tick = 0
        val r = object : Runnable {
            override fun run() {
                if (!polling) return
                send(if (tick % 6 == 0) Protocol.readSettings() else Protocol.readStatus())
                tick++
                main.postDelayed(this, 6000) // BrightEMS polls every ~6-10 s
            }
        }
        r.run()
    }

    /** Settings write (verified frame, see Protocol.writeHolding) followed by a read-back that reports whether the value arrived. */
    private fun writeSetting(label: String, reg: Int, value: Int, shown: String) {
        val frame = Protocol.writeHolding(reg, value)
        say(str(R.string.setting_progress, label), 15000)
        addChange("${stamp()}  TX $label H$reg=$value  ${frame.joinToString("") { "%02x".format(it) }}")
        send(frame)
        main.postDelayed({ send(Protocol.readSettings()) }, 1500)
        main.postDelayed({
            val now = settingsRegs.value.getOrNull(reg)
            say(if (now == value) str(R.string.setting_done, label, shown) else str(R.string.setting_mismatch, label, now?.toString() ?: "–", value.toString()))
        }, 4000)
    }

    /** Display timeout = settings register 25 in seconds (verified from a BrightEMS capture). 10..1800 s, never 0. */
    fun setScreenTimeout(seconds: Int) {
        if (seconds !in 10..1800) { say(str(R.string.err_timeout_range)); return }
        writeSetting(str(R.string.label_display_timeout), 25, seconds, fmtSeconds(seconds))
    }

    /** Upper charge limit = settings register 27, raw = percent x 10. Allowed 10..100 %. */
    fun setChargeMax(percent: Int) {
        val lower = (settingsRegs.value.getOrNull(26) ?: 0) / 10
        if (percent !in 10..100) { say(str(R.string.err_charge_range, 10, 100)); return }
        if (percent < lower + 5) { say(str(R.string.err_charge_vs_discharge, 5, lower)); return }
        writeSetting(str(R.string.label_charge_limit), 27, percent * 10, "$percent %")
    }

    /** Lower discharge limit = settings register 26, raw = percent x 10. Allowed 0..50 %. */
    fun setDischargeMin(percent: Int) {
        val upper = (settingsRegs.value.getOrNull(27) ?: 1000) / 10
        if (percent !in 0..50) { say(str(R.string.err_discharge_range, 0, 50)); return }
        if (percent > upper - 5) { say(str(R.string.err_discharge_vs_charge, 5, upper)); return }
        writeSetting(str(R.string.label_discharge_limit), 26, percent * 10, "$percent %")
    }

    /** Light mode = status register 79 (0 off, 1 on, 2 SOS, 3 flashing). Write frame confirmed working on the unit. */
    fun setLightMode(mode: Int) {
        if (mode !in 0..3) return
        val frame = Protocol.writeLikeState(79, mode)
        addChange("${stamp()}  TX light mode=$mode  ${frame.joinToString("") { "%02x".format(it) }}")
        send(frame)
        main.postDelayed({ pollNow() }, 1200)
    }

    /** Switch one output by rewriting the state bitmask (register 75), like BrightEMS does for USB. */
    fun setOutput(name: String, bit: Int, on: Boolean) {
        val cur = status.value?.state ?: run { say(str(R.string.no_data_yet)); return }
        val new = if (on) cur or bit else cur and bit.inv()
        val frame = Protocol.writeState(new)
        addChange("${stamp()}  TX $name ${if (on) "on" else "off"}: state %04x -> %04x  ${frame.joinToString("") { "%02x".format(it) }}".format(cur, new))
        send(frame)
        main.postDelayed({ send(Protocol.readStatus()) }, 1200)
        main.postDelayed({
            val now = status.value?.state ?: return@postDelayed
            if ((now and bit != 0) != on) say(str(R.string.not_switched, name))
        }, 3000)
    }
}
