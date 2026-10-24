package com.papahchan.papahnow.input

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.papahchan.papahnow.webrtc.GfnPeerConnection
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Controller managing physical gamepad events and debug gamepad triggers,
 * packing states into 54-byte GFN packets and delivering them to GfnPeerConnection.
 */
class GfnGamepadController(
    private val peerConnectionProvider: () -> GfnPeerConnection?
) {
    companion object {
        private const val TAG = "GfnGamepadController"
        private const val STICK_DEADZONE = 0.05f

        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString(" ") { "%02x".format(it) }
    }

    @Volatile
    var currentState: GamepadState = GamepadState()
        private set

    private var sequence: Byte = 0
    val totalPacketsSent = AtomicInteger(0)

    /**
     * Sends the current gamepad state to the server via input_channel_partially_reliable.
     * Buttons and analog axes are independent: release packets carry the last analog position,
     * and stick movements preserve currently pressed buttons.
     */
    @Synchronized
    fun sendCurrentState(): Boolean {
        val pc = peerConnectionProvider()
        if (pc == null || !pc.isGamepadChannelOpen()) {
            Log.w(TAG, "Cannot send gamepad packet: input_channel_partially_reliable is not OPEN")
            return false
        }

        val seq = sequence++
        val packet = GfnGamepadPacket.build(currentState, seq)
        val sent = pc.sendGamepadPacket(packet)
        if (sent) {
            totalPacketsSent.incrementAndGet()
            Log.d(TAG, "Sent gamepad packet [seq=$seq, buttons=0x${Integer.toHexString(currentState.buttons)}, " +
                "L=(${currentState.leftStickX}, ${currentState.leftStickY}), " +
                "R=(${currentState.rightStickX}, ${currentState.rightStickY})]: ${bytesToHex(packet)}")
        } else {
            Log.w(TAG, "Failed to send gamepad packet to WebRTC DataChannel")
        }
        return sent
    }

    /**
     * Handles hardware gamepad button events from onKeyDown / onKeyUp.
     * Returns true if the key was recognized and handled as a gamepad button.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val buttonBit = mapKeyCodeToButtonBit(event.keyCode) ?: return false

        val isDown = event.action == KeyEvent.ACTION_DOWN
        val currentButtons = currentState.buttons

        val newButtons = if (isDown) {
            currentButtons or buttonBit
        } else {
            currentButtons and buttonBit.inv()
        }

        // Only transmit if the button state actually changed (or first event)
        if (newButtons != currentButtons || event.repeatCount == 0) {
            currentState = currentState.copy(buttons = newButtons)
            sendCurrentState()
        }
        return true
    }

    /**
     * Handles hardware joystick / analog motion events from onGenericMotionEvent.
     * Returns true if the motion event was from a joystick/gamepad device.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        val source = event.source
        val isJoystick = (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

        if (!isJoystick) return false

        // Left analog stick
        val rawLx = event.getAxisValue(MotionEvent.AXIS_X)
        val rawLy = event.getAxisValue(MotionEvent.AXIS_Y)
        val lx = if (abs(rawLx) > STICK_DEADZONE) rawLx else 0f
        val ly = if (abs(rawLy) > STICK_DEADZONE) rawLy else 0f

        // Right analog stick (AXIS_Z / AXIS_RZ or fallback AXIS_RX / AXIS_RY)
        var rawRx = event.getAxisValue(MotionEvent.AXIS_Z)
        if (rawRx == 0f) rawRx = event.getAxisValue(MotionEvent.AXIS_RX)
        var rawRy = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (rawRy == 0f) rawRy = event.getAxisValue(MotionEvent.AXIS_RY)
        val rx = if (abs(rawRx) > STICK_DEADZONE) rawRx else 0f
        val ry = if (abs(rawRy) > STICK_DEADZONE) rawRy else 0f

        // D-Pad Hat axes (on some controllers D-pad is reported via AXIS_HAT_X / AXIS_HAT_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var dpadButtons = currentState.buttons and
            (GfnGamepadPacket.DPAD_UP or GfnGamepadPacket.DPAD_DOWN or
                GfnGamepadPacket.DPAD_LEFT or GfnGamepadPacket.DPAD_RIGHT).inv()

        if (hatY < -0.5f) dpadButtons = dpadButtons or GfnGamepadPacket.DPAD_UP
        if (hatY > 0.5f) dpadButtons = dpadButtons or GfnGamepadPacket.DPAD_DOWN
        if (hatX < -0.5f) dpadButtons = dpadButtons or GfnGamepadPacket.DPAD_LEFT
        if (hatX > 0.5f) dpadButtons = dpadButtons or GfnGamepadPacket.DPAD_RIGHT

        currentState = currentState.copy(
            buttons = dpadButtons,
            leftStickX = lx,
            leftStickY = ly,
            rightStickX = rx,
            rightStickY = ry
        )
        sendCurrentState()
        return true
    }

    /**
     * Executes an automated test sequence of gamepad packets:
     * 1. Neutral packet (all 0)
     * 2. Press Button A (0x10)
     * 3. Release Button A
     * 4. Move Left Stick Right (+1.0) & Up (-1.0, negated to positive in packet)
     * 5. Move Right Stick Right (+1.0)
     * 6. Return all controls to neutral
     */
    suspend fun runDebugSequence(onStep: (String) -> Unit): Boolean {
        val pc = peerConnectionProvider()
        if (pc == null || !pc.isGamepadChannelOpen()) {
            val msg = "ERROR: Gamepad channel is not OPEN (diagnostics: ${pc?.dataChannelDiagnostics() ?: "pc=null"})"
            Log.e(TAG, msg)
            onStep(msg)
            return false
        }

        onStep("Starting debug gamepad sequence...")
        delay(100)

        // Step 1: Neutral
        currentState = GamepadState()
        sendCurrentState()
        onStep("Step 1/5: Sent neutral state (all 0)")
        delay(300)

        // Step 2: Press Button A
        currentState = currentState.copy(buttons = GfnGamepadPacket.BUTTON_A)
        sendCurrentState()
        onStep("Step 2/5: Pressed Button A (bitmask 0x10)")
        delay(500)

        // Step 3: Release Button A
        currentState = currentState.copy(buttons = 0)
        sendCurrentState()
        onStep("Step 3/5: Released Button A")
        delay(300)

        // Step 4: Move Left Stick Right (+1.0) and Up (-1.0)
        currentState = currentState.copy(leftStickX = 1.0f, leftStickY = -1.0f)
        sendCurrentState()
        onStep("Step 4/5: Moved Left Stick (X=+1.0, Y=-1.0/Up)")
        delay(500)

        // Step 5: Move Right Stick Right (+1.0)
        currentState = currentState.copy(leftStickX = 0.0f, leftStickY = 0.0f, rightStickX = 1.0f, rightStickY = 0.0f)
        sendCurrentState()
        onStep("Step 5/5: Moved Right Stick (X=+1.0)")
        delay(500)

        // Step 6: Return to neutral
        currentState = GamepadState()
        sendCurrentState()
        onStep("Complete: Gamepad reset to neutral. Total sent: ${totalPacketsSent.get()}")
        return true
    }

    private fun mapKeyCodeToButtonBit(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> GfnGamepadPacket.BUTTON_A
        KeyEvent.KEYCODE_BUTTON_B -> GfnGamepadPacket.BUTTON_B
        KeyEvent.KEYCODE_BUTTON_X -> GfnGamepadPacket.BUTTON_X
        KeyEvent.KEYCODE_BUTTON_Y -> GfnGamepadPacket.BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_L1 -> GfnGamepadPacket.BUTTON_LB
        KeyEvent.KEYCODE_BUTTON_R1 -> GfnGamepadPacket.BUTTON_RB
        KeyEvent.KEYCODE_BUTTON_L2 -> GfnGamepadPacket.BUTTON_LT
        KeyEvent.KEYCODE_BUTTON_R2 -> GfnGamepadPacket.BUTTON_RT
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> GfnGamepadPacket.BUTTON_SELECT
        KeyEvent.KEYCODE_BUTTON_START -> GfnGamepadPacket.BUTTON_START
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GfnGamepadPacket.BUTTON_L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GfnGamepadPacket.BUTTON_R3
        KeyEvent.KEYCODE_DPAD_UP -> GfnGamepadPacket.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GfnGamepadPacket.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GfnGamepadPacket.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GfnGamepadPacket.DPAD_RIGHT
        else -> null
    }
}
