package network.xyo.modbluetoothkotlin.advertiser

import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import network.xyo.ble.gatt.server.XYBluetoothAdvertiser
import network.xyo.modbluetoothkotlin.XyoUuids

/**
 * A class for managing XYO advertising.
 *
 * @property id The device ID to advertise (recommended 2 bytes)
 * @param advertiser The XY advertiser to advertise with.
 */
class XyoBluetoothAdvertiser (val id : ByteArray, private val advertiser: XYBluetoothAdvertiser) {
    /**
     * Start a advertisement cycle
     */
    fun startAdvertiserFirst() = GlobalScope.async {
        val manData = advertiser.changeManufacturerData(id, false).await()
        if (manData.error != null) return@async manData.error

        val manId = advertiser.changeManufacturerId(13, false).await()
        if (manId.error != null) return@async manId.error

        val conResult = advertiser.changeContactable(true, false).await()
        if (conResult.error != null) return@async conResult.error

        val modResult = advertiser.changeAdvertisingMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY, false).await()
        if (modResult.error != null) return@async modResult.error

        val levResult = advertiser.changeAdvertisingTxLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH, false).await()
        if (levResult.error != null) return@async levResult.error

        return@async advertiser.changePrimaryService(ParcelUuid(XyoUuids.XYO_SERVICE), false).await().error
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
}