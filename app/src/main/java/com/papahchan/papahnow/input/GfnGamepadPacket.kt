package com.papahchan.papahnow.input

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gamepad state representation (buttons + 2 sticks).
 * Buttons use a 24-bit bitmask where button index i corresponds to bit (4 + i).
 * Analog axes are normalized in the range -1.0f .. 1.0f.
 */
data class GamepadState(
    val buttons: Int = 0,
    val leftStickX: Float = 0f,
    val leftStickY: Float = 0f,
    val rightStickX: Float = 0f,
    val rightStickY: Float = 0f
)

/**
 * 54-byte binary gamepad packet builder for GFN WebRTC DataChannel (input_channel_partially_reliable).
 *
 * Packet format decoded from WebRTC DataChannel capture on GFN:
 * - Bytes 0-4: Magic 23 00 00 00 00
 * - Bytes 5-8: Monotonic timestamp (uint32 little-endian)
 * - Byte 9: 0x26
 * - Bytes 10-11: 00 00
 * - Byte 12: Sequence counter
 * - Byte 13: 0x21
 * - Byte 14: 00
 * - Byte 15: 0x26
 * - Bytes 16-19: 0c 00 00 00 (int32 = 12)
 * - Byte 20: 0x1a (26)
 * - Bytes 21-23: 00 00 00
 * - Bytes 24-25: 01 01
 * - Byte 26: 0x14 (20)
 * - Bytes 27-28: 00 00
 * - Bytes 29-31: 24-bit button bitmask (formula: button i -> bit (4+i))
 * - Bytes 32-33: Left Stick X (int16 little-endian: -32768..32767)
 * - Bytes 34-35: Left Stick Y (int16 little-endian, NEGATED: packet_y = -w3c_y)
 * - Bytes 36-37: Right Stick X (int16 little-endian: -32768..32767)
 * - Bytes 38-39: Right Stick Y (int16 little-endian, NEGATED: packet_y = -w3c_y)
 * - Bytes 40-41: 00 00
 * - Byte 42: 0x55
 * - Bytes 43-46: 00 00 00 00
 * - Bytes 47-49: Echo 3 first bytes of timestamp (bytes 5, 6, 7)
 * - Byte 50: Sequence / varying byte
 * - Bytes 51-53: 00 00 00
 */
object GfnGamepadPacket {
    const val PACKET_SIZE = 54

    // Button bitmask definitions (formula: button index i -> bit (4 + i))
    // Byte 29: bits 4..7
    const val BUTTON_A = 1 shl 4       // 0x10 (index 0)
    const val BUTTON_B = 1 shl 5       // 0x20 (index 1)
    const val BUTTON_X = 1 shl 6       // 0x40 (index 2)
    const val BUTTON_Y = 1 shl 7       // 0x80 (index 3)

    // Byte 30: bits 8..15 (bits 0..7 of byte 30)
    const val BUTTON_LB = 1 shl 8      // 0x01 in byte 30 (index 4)
    const val BUTTON_RB = 1 shl 9      // 0x02 in byte 30 (index 5)
    const val BUTTON_LT = 1 shl 10     // 0x04 in byte 30 (index 6)
    const val BUTTON_RT = 1 shl 11     // 0x08 in byte 30 (index 7)
    const val BUTTON_SELECT = 1 shl 12 // 0x10 in byte 30 (index 8)
    const val BUTTON_START = 1 shl 13  // 0x20 in byte 30 (index 9)
    const val BUTTON_L3 = 1 shl 14     // 0x40 in byte 30 (index 10)
    const val BUTTON_R3 = 1 shl 15     // 0x80 in byte 30 (index 11)

    // Byte 31: bits 16..19 (bits 0..3 of byte 31)
    const val DPAD_UP = 1 shl 16       // 0x01 in byte 31 (index 12)
    const val DPAD_DOWN = 1 shl 17     // 0x02 in byte 31 (index 13)
    const val DPAD_LEFT = 1 shl 18     // 0x04 in byte 31 (index 14)
    const val DPAD_RIGHT = 1 shl 19    // 0x08 in byte 31 (index 15)

    fun build(
        state: GamepadState,
        sequence: Byte,
        monotonicTimestampMs: Long = SystemClock.uptimeMillis()
    ): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // Bytes 0-4: Magic 23 00 00 00 00
        buffer.put(0x23.toByte())
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())

        // Bytes 5-8: Monotonic timestamp (32-bit uint LE)
        val ts32 = (monotonicTimestampMs and 0xFFFFFFFFL).toInt()
        buffer.putInt(ts32)

        // Byte 9: 0x26
        buffer.put(0x26.toByte())

        // Bytes 10-11: 00 00
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())

        // Byte 12: Sequence counter
        buffer.put(sequence)

        // Byte 13: 0x21
        buffer.put(0x21.toByte())

        // Byte 14: 00
        buffer.put(0x00.toByte())

        // Byte 15: 0x26
        buffer.put(0x26.toByte())

        // Bytes 16-19: 0c 00 00 00 (int32 = 12)
        buffer.putInt(12)

        // Byte 20: 0x1a
        buffer.put(0x1a.toByte())

        // Bytes 21-23: 00 00 00
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())

        // Bytes 24-25: 01 01
        buffer.put(0x01.toByte())
        buffer.put(0x01.toByte())

        // Byte 26: 0x14
        buffer.put(0x14.toByte())

        // Bytes 27-28: 00 00
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())

        // Bytes 29-31: 24-bit button bitmask LE
        buffer.put((state.buttons and 0xFF).toByte())
        buffer.put(((state.buttons shr 8) and 0xFF).toByte())
        buffer.put(((state.buttons shr 16) and 0xFF).toByte())

        // Bytes 32-33: Left Stick X (-32768..32767)
        val lxShort = (state.leftStickX.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        buffer.putShort(lxShort)

        // Bytes 34-35: Left Stick Y (NEGATED! packet_y = -w3c_y)
        val lyShort = ((-state.leftStickY).coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        buffer.putShort(lyShort)

        // Bytes 36-37: Right Stick X (-32768..32767)
        val rxShort = (state.rightStickX.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        buffer.putShort(rxShort)

        // Bytes 38-39: Right Stick Y (NEGATED! packet_y = -w3c_y)
        val ryShort = ((-state.rightStickY).coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        buffer.putShort(ryShort)

        // Bytes 40-41: 00 00 (Reserved)
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())

        // Byte 42: 0x55
        buffer.put(0x55.toByte())

        // Bytes 43-46: 00 00 00 00
        buffer.putInt(0)

        // Bytes 47-49: Echo 3 first bytes of timestamp (bytes 5, 6, 7)
        val raw = buffer.array()
        buffer.put(raw[5])
        buffer.put(raw[6])
        buffer.put(raw[7])

        // Byte 50: Sequence / varying
        buffer.put(sequence)

        // Bytes 51-53: 00 00 00
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())
        buffer.put(0x00.toByte())

        return buffer.array()
    }
}
