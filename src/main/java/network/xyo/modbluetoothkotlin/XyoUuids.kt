package network.xyo.modbluetoothkotlin

import java.util.*

/**
 * All the XYO Bluetooth UUIDs.
 */
object XyoUuids {
    /**
     * The primary GATT service that will be advertised.
     */
    // d684352e-df36-484e-bc98-2d5398c5593e
    val XYO_SERVICE = UUID.fromString("d684352e-df36-484e-bc98-2d5398c5593e")!!

    /**
     * The GATT characteristic to be written to. This will be in the XYO_SERVICE.
     */
    val XYO_WRITE = UUID.fromString("fffa3639-0eb4-4525-b1bc-7fa456490b2d")!!

    val NOTIFY_DESCREPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")!!
}