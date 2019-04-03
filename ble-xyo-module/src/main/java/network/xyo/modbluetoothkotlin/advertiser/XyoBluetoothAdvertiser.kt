package network.xyo.modbluetoothkotlin.advertiser

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import network.xyo.ble.gatt.server.XYBluetoothAdvertiser
import network.xyo.ble.gatt.server.XYIBeaconAdvertiseDataCreator
import network.xyo.modbluetoothkotlin.XyoUuids
import network.xyo.sdkobjectmodelkotlin.objects.toHexString
import java.nio.ByteBuffer
import java.util.*

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
        if (advertiser.isMultiAdvertisementSupported) {
            configureAdverserMulti()
            return
        }
        configureAdvertiserSingle()
    }

//    private fun getAdvertiseUuid (uuid: UUID, major: ByteArray, minor: ByteArray): UUID {
//        val uuidString = uuid.toString().dropLast(8)
//        val majorString = major.toHexString().drop(2)
//        val minorString = minor.toHexString().drop(2)
//
//        return UUID.fromString(  minorString + majorString + uuidString)
//    }

    private fun configureAdverserMulti () {
        val encodeMajor = ByteBuffer.allocate(2).putShort(major).array()
        val encodedMinor = ByteBuffer.allocate(2).putShort(minor).array()
        val advertiseData =  XYIBeaconAdvertiseDataCreator.create(
                encodeMajor,
                encodedMinor,
                XyoUuids.XYO_SERVICE,
                APPLE_MANUFACTURER_ID,
                false
        ).build()

        val responseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(
                        XyoUuids.XYO_SERVICE
                ))
                .build()

        advertiser.advertisingData = advertiseData
        advertiser.advertisingResponse = responseData
        advertiser.changeContactable(true)
        advertiser.changeAdvertisingMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        advertiser.changeAdvertisingTxLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
    }

    private fun configureAdvertiserSingle () {
        val advertiseData = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(XyoUuids.XYO_SERVICE))
                .setIncludeDeviceName(true)
                .build()

        advertiser.advertisingData = advertiseData
        advertiser.changeContactable(true)
        advertiser.changeAdvertisingMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        advertiser.changeAdvertisingTxLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
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