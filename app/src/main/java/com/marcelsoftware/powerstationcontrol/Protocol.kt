package com.marcelsoftware.powerstationcontrol

import java.util.UUID

/**
 * BLE protocol used by SYDPOWER / Fossibot / BrightEMS-compatible units (Aferiy included).
 * Source: ESP-FBot project (github.com/Ylianst/ESP-FBot). NOT yet verified on a P180.
 * Frames are Modbus-like: 0x11 <function> ... <CRC16 hi> <CRC16 lo>.
 */
object Protocol {
    val SERVICE: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR: UUID = UUID.fromString("0000c304-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR: UUID = UUID.fromString("0000c305-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val ADDR = 0x11
    const val FN_READ_SETTINGS = 0x03
    const val FN_READ_STATUS = 0x04
    const val FN_WRITE_STATE = 0x05

    // Verified against a BrightEMS capture on an Aferiy P180:
    const val REG_BATTERY_PCT = 31   // status register, plain percent (67 == 67 %)
    const val REG_STATE = 75         // status register; bitmask, also the target of control writes
    const val STATE_BASE = 0x80      // always set in captures
    const val BIT_USB = 0x08         // USB output on (0x80 -> 0x88), verified by capture
    const val BIT_DC = 0x04          // DC / car socket (from amukan/aferiy-p180-esphome, not yet verified by us)
    const val BIT_AC = 0x10          // AC inverter (same source, not yet verified by us)
    const val REG_COUNT = 100

    /** CRC-16/MODBUS (reflected 0xA001, init 0xFFFF), sent high byte first. */
    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    private fun withCrc(body: ByteArray): ByteArray {
        val c = crc16(body)
        return body + byteArrayOf((c shr 8).toByte(), c.toByte())
    }

    private fun read(fn: Int) = withCrc(byteArrayOf(ADDR.toByte(), fn.toByte(), 0, 0, 0, REG_COUNT.toByte()))

    fun readStatus() = read(FN_READ_STATUS)       // 11 04 00 00 00 64 71 F3
    fun readSettings() = read(FN_READ_SETTINGS)   // 11 03 00 00 00 64 B1 46

    /** State write as sent by BrightEMS: 11 05 <reg> 00 01 <value hi> <value lo> <crc>. */
    fun writeState(value: Int) = withCrc(byteArrayOf(
        ADDR.toByte(), FN_WRITE_STATE.toByte(), REG_STATE.toByte(), 0, 1, (value shr 8).toByte(), value.toByte()))

    /**
     * Settings write, VERIFIED from a BrightEMS capture on a P180 (screen timeout 300 s):
     * 11 06 <reg8> 00 01 <value16> <crc>  e.g. 11 06 19 00 01 01 2C 28 56. Unit echoes the frame on success.
     * (The plain Modbus layout 11 06 <reg16> <value16> is rejected with 11 86 ...)
     */
    fun writeHolding(reg: Int, value: Int) = withCrc(byteArrayOf(
        ADDR.toByte(), 6, reg.toByte(), 0, 1, (value shr 8).toByte(), value.toByte()))

    /** EXPERIMENTAL: same layout as the verified state write but for another register: 11 05 <reg8> 00 01 <value16> <crc>. */
    fun writeLikeState(reg: Int, value: Int) = withCrc(byteArrayOf(
        ADDR.toByte(), FN_WRITE_STATE.toByte(), reg.toByte(), 0, 1, (value shr 8).toByte(), value.toByte()))

    /** Total frame length once the 6-byte header is known; null if still unknown. */
    fun expectedLength(buf: ByteArray): Int? {
        if (buf.size < 2) return null
        return when (buf[1].toInt() and 0xFF) {
            FN_READ_STATUS, FN_READ_SETTINGS -> if (buf.size < 6) null
                else 8 + 2 * (((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF))
            FN_WRITE_STATE -> 9
            else -> buf.size
        }
    }

    fun crcOk(f: ByteArray) = f.size >= 4 &&
        crc16(f, f.size - 2) == (((f[f.size - 2].toInt() and 0xFF) shl 8) or (f[f.size - 1].toInt() and 0xFF))

    class Frame(val function: Int, val bytes: ByteArray, val crcOk: Boolean) {
        val registers: List<Int> get() =
            if (function == FN_READ_STATUS || function == FN_READ_SETTINGS)
                List(maxOf(0, (bytes.size - 8) / 2)) { i ->
                    ((bytes[6 + 2 * i].toInt() and 0xFF) shl 8) or (bytes[7 + 2 * i].toInt() and 0xFF) }
            else emptyList()
    }

    fun toFrame(bytes: ByteArray) = Frame(bytes[1].toInt() and 0xFF, bytes, crcOk(bytes))
}

data class Status(val registers: List<Int>) {
    val batteryPct: Int? get() = registers.getOrNull(Protocol.REG_BATTERY_PCT)
    val state: Int get() = registers.getOrNull(Protocol.REG_STATE) ?: 0
    val usbOn: Boolean get() = state and Protocol.BIT_USB != 0
    val dcOn: Boolean get() = state and Protocol.BIT_DC != 0
    val acOn: Boolean get() = state and Protocol.BIT_AC != 0

    // Read-only values from the amukan/aferiy-p180-esphome register map (R31/R75/R78 confirmed by us, rest not yet).
    private fun r(i: Int) = registers.getOrNull(i) ?: 0
    val acChargeW: Int get() = r(2)
    val dcInW: Int get() = r(3)
    val acInV: Double get() = r(8) / 10.0
    val acInHz: Double get() = r(9) / 100.0
    val acOutV: Double get() = r(10) / 10.0
    val acOutHzSet: Double get() = r(11) / 10.0
    val acOutW: Int get() = r(12)
    val dcUsbOutW: Int get() = r(78)
    /** USB total (R13): equals R78 with DC off; same values in the PD 20 W and QC 3.0 ports. No per-port registers exist. */
    val usbOutW: Int get() = r(13)
    /** DC output derived as R78 - R13 (UNVERIFIED: needs a DC load to confirm). */
    val dcOutW: Int get() = maxOf(0, r(78) - r(13))
    val timeToFullMin: Int get() = r(71)
    val remainingMin: Int get() = r(72)
    val acPresent: Boolean get() = acInV > 100
    val gridInW: Int get() = if (acPresent) acChargeW + acOutW else 0
    val totalInW: Int get() = gridInW + dcInW
    val totalOutW: Int get() = acOutW + dcUsbOutW
}
