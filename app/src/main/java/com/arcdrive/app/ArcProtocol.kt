package com.arcdrive.app

import java.util.UUID

object ArcProtocol {
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val DEVICE_NAME = "ArcDrive_1"

    // Commands (App -> ESP32)
    const val CMD_START: Byte = 0x01
    const val CMD_STOP: Byte = 0x02
    const val CMD_SET_SPEED: Byte = 0x03
    const val CMD_FORWARD: Byte = 0x04
    const val CMD_REVERSE: Byte = 0x05

    // Feedback (ESP32 -> App), 5-byte frame: AA TYPE HI LO CHK
    const val FRAME_HEADER: Int = 0xAA
    const val FB_START_ACK: Int = 0x81
    const val FB_STOP_ACK: Int = 0x82
    const val FB_SPEED_ACK: Int = 0x83
    const val FB_DIR_ACK: Int = 0x84
    const val FB_ERROR: Int = 0xEE

    // Timeouts
    const val ACK_TIMEOUT_MS: Long = 2000L
}
