package com.marcelsoftware.powerstationcontrol

import org.junit.Assert.*
import org.junit.Test

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Vectors taken from a real BrightEMS <-> Aferiy P180 Bluetooth capture. */
class ProtocolTest {
    @Test fun crcKnownVector() = assertEquals(0x4B37, Protocol.crc16("123456789".toByteArray()))
    @Test fun readStatusMatchesCapture() = assertArrayEquals(hex("11040000006471f3"), Protocol.readStatus())
    @Test fun readSettingsMatchesCapture() = assertArrayEquals(hex("110300000064b146"), Protocol.readSettings())
    @Test fun usbOnMatchesCapture() = assertArrayEquals(hex("11054b00010088fcef"), Protocol.writeState(0x88))
    @Test fun usbOffMatchesCapture() = assertArrayEquals(hex("11054b000100803aee"), Protocol.writeState(0x80))
    @Test fun expectedLengths() {
        assertEquals(208, Protocol.expectedLength(hex("110400000064")))
        assertEquals(9, Protocol.expectedLength(hex("1105")))
    }
    @Test fun statusParsing() {
        val raw = ByteArray(208); raw[0] = 0x11; raw[1] = 0x04; raw[5] = 0x64
        fun set(i: Int, v: Int) { raw[6 + 2 * i] = (v shr 8).toByte(); raw[7 + 2 * i] = v.toByte() }
        set(31, 67); set(75, 0x88)
        val s = Status(Protocol.toFrame(raw).registers)
        assertEquals(67, s.batteryPct); assertTrue(s.usbOn)
    }
    @Test fun screenTimeoutWriteMatchesCapture() = assertArrayEquals(hex("1106190001012c2856"), Protocol.writeHolding(25, 300))
    @Test fun lightModeFrameLayout() {
        val f = Protocol.writeLikeState(79, 2)
        assertArrayEquals(hex("11054f00010002"), f.copyOf(7)); assertTrue(Protocol.crcOk(f))
    }
    @Test fun derivedPowerValues() {
        val regs = MutableList(100) { 0 }
        regs[2] = 100; regs[3] = 20; regs[8] = 2300; regs[12] = 40; regs[78] = 5
        val s = Status(regs)
        assertTrue(s.acPresent); assertEquals(140, s.gridInW); assertEquals(160, s.totalInW); assertEquals(45, s.totalOutW)
    }
    @Test fun chargeLimitFrameIsValid() = assertTrue(Protocol.crcOk(Protocol.writeHolding(27, 800)))
    @Test fun usbAndDcOutputSplit() {
        val regs = MutableList(100) { 0 }; regs[13] = 13; regs[78] = 13
        assertEquals(13, Status(regs).usbOutW); assertEquals(0, Status(regs).dcOutW)
        regs[78] = 30; assertEquals(17, Status(regs).dcOutW)
    }
}
