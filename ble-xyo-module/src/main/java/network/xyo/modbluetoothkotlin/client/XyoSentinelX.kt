package network.xyo.modbluetoothkotlin.client

import android.content.Context
import kotlinx.coroutines.Deferred
import network.xyo.ble.devices.XYAppleBluetoothDevice
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYCreator
import network.xyo.ble.gatt.peripheral.XYBluetoothError
import network.xyo.ble.gatt.peripheral.XYBluetoothResult
import network.xyo.ble.scanner.XYScanResult
import network.xyo.modbluetoothkotlin.XyoUuids
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.experimental.and

open class XyoSentinelX(context: Context, private val scanResult: XYScanResult, hash : Int) : XyoBluetoothClient(context, scanResult, hash) {
    private val sentinelListeners = HashMap<String, Listener>()

    fun addButtonListener (key : String, listener : Listener) {
        sentinelListeners[key] = listener
    }

    fun removeButtonListener (key: String) {
        sentinelListeners.remove(key)
    }

    fun isClaimed () : Boolean {
        val iBeaconData = scanResult.scanRecord?.getManufacturerSpecificData(0x4c) ?: return true

        if (iBeaconData.size == 23) {
            val flags = iBeaconData[21]
            return flags and 1.toByte() != 0.toByte()
        }

        return true
    }

    private fun isButtonPressed (scanResult: XYScanResult) : Boolean {
        val iBeaconData = scanResult.scanRecord?.getManufacturerSpecificData(0x4c) ?: return true

        if (iBeaconData.size == 23) {
            val flags = iBeaconData[21]
            return flags and 2.toByte() != 0.toByte()
        }

        return false
    }

    override fun onDetect(scanResult: XYScanResult?) {
        if (scanResult != null && isButtonPressed(scanResult)) {
            // button of sentinel x is pressed
            for ((_, l) in this.sentinelListeners) {
                l.onButtonPressed()
            }

            return
        }

        return
    }

    /**
     * Changes the password on the remote device if the current password is correct.
     *
     * @param password The password of the device now.
     * @param newPassword The password to change on the remote device.
     * @return An XYBluetoothError if there was an issue writing the packet.
     */
    fun changePassword (password: ByteArray, newPassword: ByteArray) : Deferred<XYBluetoothError?> {
        val encoded = ByteBuffer.allocate(2 + password.size + newPassword.size)
                .put((password.size + 1).toByte())
                .put(password)
                .put((newPassword.size + 1).toByte())
                .put(newPassword)
                .array()

        return chunkSend(encoded, XyoUuids.XYO_PIN, XyoUuids.XYO_SERVICE, 1)
    }

    /**
     * Changes the bound witness data on the remote device
     *
     * @param boundWitnessData The data to include in tche remote devices bound witness.
     * @param password The password of the device to so it can write the boundWitnessData
     * @return An XYBluetoothError if there was an issue writing the packet.
     */
    fun changeBoundWitnessData (password: ByteArray, boundWitnessData: ByteArray) : Deferred<XYBluetoothError?> {
        val encoded = ByteBuffer.allocate(3 + password.size + boundWitnessData.size)
                .put((password.size + 1).toByte())
                .put(password)
                .putShort((boundWitnessData.size + 2).toShort())
                .put(boundWitnessData)
                .array()

        return chunkSend(encoded, XyoUuids.XYO_BW, XyoUuids.XYO_SERVICE, 4)
    }

    fun getBoundWitnessData () : Deferred<XYBluetoothResult<ByteArray>> {
        return findAndReadCharacteristicBytes(XyoUuids.XYO_SERVICE, XyoUuids.XYO_BW)
    }

    companion object : XYCreator() {

        open class Listener {
            open fun onButtonPressed () {}
        }

        fun enable (enable : Boolean) {
            if (enable) {
                XyoBluetoothClient.xyoManufactorIdToCreator[0x01] = this
            } else {
                XyoBluetoothClient.xyoManufactorIdToCreator.remove(0x01)
            }
        }

        override fun getDevicesFromScanResult(context: Context, scanResult: XYScanResult, globalDevices: ConcurrentHashMap<String, XYBluetoothDevice>, foundDevices: HashMap<String, XYBluetoothDevice>) {
            val hash = scanResult.scanRecord?.getManufacturerSpecificData(XYAppleBluetoothDevice.MANUFACTURER_ID)?.contentHashCode() ?: 0
            val createdDevice = XyoSentinelX(context, scanResult, hash)
            foundDevices[hash.toString()] = createdDevice
            globalDevices[hash.toString()] = createdDevice
        }
    }
}