package network.xyo.modbluetoothkotlin

import java.util.*

/**
 * All the XYO Bluetooth UUIDs.
 */
object XyoUuids {
    /**
     * The primary GATT service that will be advertised.
     */
    val XYO_SERVICE = UUID.fromString("113e4d1d-981d-4ad9-9826-e75c7b4fbf45")!!

    /**
     * The GATT characteristic to be written to. This will be in the XYO_SERVICE.
     */
    val XYO_WRITE = UUID.fromString("222e4d1d-981d-4ad9-9816-e75c7b4fbf42")!!
}