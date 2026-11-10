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
            chip = when (u8(52)) { 0 -> "ESP32"; 1 -> "ESP32-C3"; 2 -> "ESP32-S3"; 3 -> "ESP32-C6"; else -> "ESP32" },
        )
    }
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
)
