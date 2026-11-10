package io.github.pixelblue.ble

import java.util.UUID

/** PixelBlue BLE protocol v1 - must match firmware/src/config.h and docs/PROTOCOL.md. */
object Protocol {
    val SERVICE: UUID = UUID.fromString("c0de0001-5ee1-4b1e-9e0d-50a3c1b2e000")
    val CMD: UUID = UUID.fromString("c0de0002-5ee1-4b1e-9e0d-50a3c1b2e000")
    val STATE: UUID = UUID.fromString("c0de0003-5ee1-4b1e-9e0d-50a3c1b2e000")
    val PIXELS: UUID = UUID.fromString("c0de0004-5ee1-4b1e-9e0d-50a3c1b2e000")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MAX_SCHEDULES = 4

    fun power(on: Boolean) = byteArrayOf(0x01, if (on) 1 else 0)
    fun brightness(v: Int) = byteArrayOf(0x02, v.toByte())
    fun color(r: Int, g: Int, b: Int, w: Int = 0) = byteArrayOf(0x03, r.toByte(), g.toByte(), b.toByte(), w.toByte())
    fun effect(id: Int) = byteArrayOf(0x04, id.toByte())
    fun speed(v: Int) = byteArrayOf(0x05, v.toByte())
    fun color2(r: Int, g: Int, b: Int) = byteArrayOf(0x0C, r.toByte(), g.toByte(), b.toByte())
    fun clear(r: Int, g: Int, b: Int) = byteArrayOf(0x0A, r.toByte(), g.toByte(), b.toByte())

    fun time(epochSeconds: Long, tzMinutes: Int): ByteArray {
        val e = epochSeconds.toInt()
        return byteArrayOf(
            0x06, e.toByte(), (e shr 8).toByte(), (e shr 16).toByte(), (e shr 24).toByte(),
            tzMinutes.toByte(), (tzMinutes shr 8).toByte(),
        )
    }

    fun schedule(slots: List<ScheduleSlot>): ByteArray {
        val list = slots.take(MAX_SCHEDULES)
        val out = ByteArray(2 + list.size * 6)
        out[0] = 0x07
        out[1] = list.size.toByte()
        list.forEachIndexed { i, s ->
            val o = 2 + i * 6
            out[o] = s.start.toByte(); out[o + 1] = (s.start shr 8).toByte()
            out[o + 2] = s.end.toByte(); out[o + 3] = (s.end shr 8).toByte()
            out[o + 4] = s.days.toByte(); out[o + 5] = if (s.enabled) 1 else 0
        }
        return out
    }

    fun config(c: HwConfig) = byteArrayOf(
        0x08, c.type.toByte(), c.order.toByte(), c.dataPin.toByte(), c.clockPin.toByte(),
        c.width.toByte(), (c.width shr 8).toByte(), c.height.toByte(), (c.height shr 8).toByte(),
        c.layout.toByte(), c.maxmA.toByte(), (c.maxmA shr 8).toByte(),
    )

    fun name(n: String): ByteArray {
        val bytes = n.trim().toByteArray(Charsets.UTF_8).take(20).toByteArray()
        return byteArrayOf(0x09) + bytes
    }

    fun text(t: String): ByteArray {
        val bytes = t.toByteArray(Charsets.UTF_8).take(120).toByteArray()
        return byteArrayOf(0x0B) + bytes
    }

    // App-internal opcodes used only for SP110E settings (translated in PixelBle).
    fun spIcModel(v: Int) = byteArrayOf(0xF1.toByte(), v.toByte())
    fun spOrder(v: Int) = byteArrayOf(0xF2.toByte(), v.toByte())
    fun spCount(n: Int) = byteArrayOf(0xF3.toByte(), n.toByte(), (n shr 8).toByte())

    /** Pixel packet: logical offset + RGB triplets. */
    fun pixels(offset: Int, rgb: ByteArray): ByteArray =
        byteArrayOf(offset.toByte(), (offset shr 8).toByte()) + rgb

    fun parseState(p: ByteArray): DeviceState? {
        if (p.size < 53) return null
        fun u8(i: Int) = p[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        val slots = (0 until MAX_SCHEDULES).map { i ->
            val o = 25 + i * 6
            ScheduleSlot(u16(o), u16(o + 2), u8(o + 4), u8(o + 5) != 0)
        }
        return DeviceState(
            power = u8(1) != 0, brightness = u8(2), effect = u8(3), speed = u8(4),
            r = u8(5), g = u8(6), b = u8(7), w = u8(8),
            config = HwConfig(u8(9), u8(10), u8(11), u8(12), u16(13), u16(15), u8(17), u16(18)),
            timeValid = u8(20) != 0, effectCount = u8(21),
            firmware = "${u8(22)}.${u8(23)}",
            schedule = slots,
            r2 = u8(49), g2 = u8(50), b2 = u8(51),
            kind = Kind.PIXELBLUE,
            chip = when (u8(52)) { 0 -> "ESP32"; 1 -> "ESP32-C3"; 2 -> "ESP32-S3"; 3 -> "ESP32-C6"; else -> "ESP32" },
        )
    }
}

enum class Kind { PIXELBLUE, SP110E }

/** Original SP110E controller (LED Hue app), community-documented protocol. */
object Sp110e {
    val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val CHAR: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val AUX: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

    const val PRESETS = 120
    const val STATIC_MODE = 121

    val IC_MODELS = listOf(
        "SM16703", "TM1804", "UCS1903", "WS2811", "WS2801", "SK6812", "LPD6803", "LPD8806",
        "APA102", "APA105", "DMX512", "TM1914", "TM1913", "P9813", "INK1003", "P943S",
        "P9411", "P9413", "TX1812", "TX1813", "GS8206", "GS8208", "SK9822", "TM1814",
        "SK6812 RGBW", "P9414", "PG412",
    )

    private fun c(a: Int, b: Int, c: Int, op: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), op.toByte())

    val CHECK = c(0xD7, 0xF3, 0xA1, 0xD5)
    val INFO = c(0, 0, 0, 0x10)

    /** Translates a PixelBlue command into SP110E packets (empty if not supported). */
    fun translate(cmd: ByteArray): List<ByteArray> {
        if (cmd.isEmpty()) return emptyList()
        fun u(i: Int) = if (i < cmd.size) cmd[i].toInt() and 0xFF else 0
        return when (u(0)) {
            0x01 -> listOf(c(0, 0, 0, if (u(1) != 0) 0xAA else 0xAB))
            0x02 -> listOf(c(u(1), 0, 0, 0x2A))
            0x03 -> listOf(c(u(1), u(2), u(3), 0x1E), c(u(4), 0, 0, 0x69))
            0x04 -> listOf(c(u(1), 0, 0, 0x2C))
            0x05 -> listOf(c(u(1), 0, 0, 0x03))
            0xF1 -> listOf(c(u(1), 0, 0, 0x1C))
            0xF2 -> listOf(c(u(1), 0, 0, 0x3C))
            0xF3 -> { val n = u(1) or (u(2) shl 8); listOf(c(n shr 8, n and 0xFF, 0, 0x2D)) }
            else -> emptyList()
        }
    }

    /** Parses the info reply (12 bytes, or 13 with a leading checksum). */
    fun parse(p: ByteArray, previous: DeviceState?): DeviceState? {
        val o = when (p.size) { 12 -> 0; 13 -> 1; else -> return null }
        fun u(i: Int) = p[o + i].toInt() and 0xFF
        val count = (u(6) shl 8) or u(7)
        return DeviceState(
            power = u(0) != 0, brightness = u(3), effect = u(1), speed = u(2),
            r = u(8), g = u(9), b = u(10), w = u(11),
            config = HwConfig(type = u(4), order = u(5), dataPin = 0, clockPin = 255, width = count.coerceAtLeast(1), height = 1, layout = 0, maxmA = 0),
            timeValid = false, effectCount = 0, firmware = "SP110E", schedule = emptyList(),
            r2 = previous?.r2 ?: 0, g2 = previous?.g2 ?: 0, b2 = previous?.b2 ?: 0,
            chip = "SP110E", kind = Kind.SP110E,
        )
    }

    fun default() = DeviceState(
        power = true, brightness = 200, effect = STATIC_MODE, speed = 128, r = 255, g = 80, b = 0, w = 0,
        config = HwConfig(type = 3, order = 2, dataPin = 0, width = 60, height = 1, layout = 0, maxmA = 0),
        timeValid = false, effectCount = 0, firmware = "SP110E", schedule = emptyList(),
        r2 = 0, g2 = 0, b2 = 0, chip = "SP110E", kind = Kind.SP110E,
    )
}

data class HwConfig(
    val type: Int = 0,
    val order: Int = 2,
    val dataPin: Int = 16,
    val clockPin: Int = 255,
    val width: Int = 60,
    val height: Int = 1,
    val layout: Int = 1,
    val maxmA: Int = 2000,
) {
    val count get() = width * height
    val needsClock get() = type == 3 || type == 4

    companion object {
        const val SERPENTINE = 1
        const val VERTICAL = 2
        const val START_RIGHT = 4
        const val START_BOTTOM = 8
    }
}

data class ScheduleSlot(
    val start: Int = 0,      // minute of day
    val end: Int = 0,
    val days: Int = 0x7F,    // bit0 = Monday ... bit6 = Sunday
    val enabled: Boolean = false,
)

data class DeviceState(
    val power: Boolean,
    val brightness: Int,
    val effect: Int,
    val speed: Int,
    val r: Int, val g: Int, val b: Int, val w: Int,
    val config: HwConfig,
    val timeValid: Boolean,
    val effectCount: Int,
    val firmware: String,
    val schedule: List<ScheduleSlot>,
    val r2: Int, val g2: Int, val b2: Int,
    val chip: String,
    val kind: Kind = Kind.PIXELBLUE,
) {
    /** Applies a PixelBlue command locally (demo mode, and instant feedback for SP110E). */
    fun apply(cmd: ByteArray): DeviceState {
        if (cmd.isEmpty()) return this
        fun u(i: Int) = if (i < cmd.size) cmd[i].toInt() and 0xFF else 0
        fun u16(i: Int) = u(i) or (u(i + 1) shl 8)
        return when (u(0)) {
            0x01 -> copy(power = u(1) != 0)
            0x02 -> copy(brightness = u(1))
            0x03 -> copy(r = u(1), g = u(2), b = u(3), w = u(4))
            0x04 -> copy(effect = u(1), power = true)
            0x05 -> copy(speed = u(1))
            0x06 -> copy(timeValid = true)
            0x07 -> copy(schedule = (0 until u(1)).map { i ->
                val o = 2 + i * 6
                ScheduleSlot(u16(o), u16(o + 2), u(o + 4), u(o + 5) != 0)
            })
            0x08 -> copy(config = HwConfig(u(1), u(2), u(3), u(4), u16(5), u16(7), u(9), u16(10)))
            0x0A -> copy(effect = 1, power = true)
            0x0B -> copy(effect = 16, power = true)
            0x0C -> copy(r2 = u(1), g2 = u(2), b2 = u(3))
            0xF1 -> copy(config = config.copy(type = u(1)))
            0xF2 -> copy(config = config.copy(order = u(1)))
            0xF3 -> copy(config = config.copy(width = u16(1).coerceAtLeast(1)))
            else -> this
        }
    }

    companion object {
        fun demo() = DeviceState(
            power = true, brightness = 160, effect = 3, speed = 128, r = 255, g = 80, b = 0, w = 0,
            config = HwConfig(width = 16, height = 16), timeValid = true, effectCount = 17,
            firmware = "demo", schedule = List(Protocol.MAX_SCHEDULES) { ScheduleSlot() },
            r2 = 0, g2 = 0, b2 = 60, chip = "Demo", kind = Kind.PIXELBLUE,
        )
    }
}
