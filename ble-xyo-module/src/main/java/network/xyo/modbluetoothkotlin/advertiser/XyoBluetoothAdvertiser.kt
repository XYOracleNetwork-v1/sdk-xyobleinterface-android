package network.xyo.modbluetoothkotlin.advertiser

import android.bluetooth.le.AdvertiseSettings
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import network.xyo.ble.gatt.server.XYBluetoothAdvertiser
import network.xyo.ble.gatt.server.XYIBeaconAdvertiseDataCreator
import network.xyo.modbluetoothkotlin.XyoUuids
import java.nio.ByteBuffer

/**
 * A class for managing XYO advertising.
 *
 * @property major The device major to advertise
 * @property minor The device minor to advertise
 * @param advertiser The XY advertiser to advertise with.
 */
class XyoBluetoothAdvertiser (private val major : Short, private val minor : Short, private val advertiser: XYBluetoothAdvertiser) {
    /**
     * Start a advertisement cycle
     */
    fun configureAdvertiser() {
        val encodeMajor = ByteBuffer.allocate(2).putShort(major).array()
        val encodedMinor = ByteBuffer.allocate(2).putShort(minor).array()
        val advertiseData =  XYIBeaconAdvertiseDataCreator.create(
                encodeMajor,
                encodedMinor,
                XyoUuids.XYO_SERVICE,
                APPLE_MANUFACTURER_ID,
                false
        )

        advertiser.advertisingData = advertiseData
        advertiser.changeContactable(false) // set this to false for iBeacon
        advertiser.changeAdvertisingMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        advertiser.changeAdvertisingTxLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
    }

    /**
     * Stop the current advertisement cycle
     */
    fun stopAdvertiser() {
        advertiser.stopAdvertising()
    }

    fun startAdvertiser () = GlobalScope.async {
        return@async advertiser.startAdvertising()
    }

    companion object {
        const val APPLE_MANUFACTURER_ID = 76
    }
}