package network.xyo.modbluetoothkotlin

import java.util.*

/**
 * All the XYO Bluetooth UUIDs.
 */
object XyoUuids {
    /**
     * The primary GATT service that will be advertised.
     */
    val XYO_SERVICE = UUID.fromString("d684352e-df36-484e-bc98-2d5398c5593e")!!

    /**
     * The GATT characteristic to be written to. This will be in the XYO_SERVICE.
     */
    val XYO_WRITE = UUID.fromString("727a3639-0eb4-4525-b1bc-7fa456490b2d")!!
}