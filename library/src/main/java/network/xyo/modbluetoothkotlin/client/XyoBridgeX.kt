package network.xyo.modbluetoothkotlin.client

import android.content.Context
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYCreator
import network.xyo.ble.scanner.XYScanResult
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@kotlin.ExperimentalUnsignedTypes
open class XyoBridgeX(context: Context, scanResult: XYScanResult, hash: String) :
        XyoBluetoothClient(context, scanResult, hash) {

    companion object : XYCreator() {

        fun enable(enable: Boolean) {
            if (enable) {
                xyoManufactureIdToCreator[XyoBluetoothClientDeviceType.BridgeX.raw] = this
            } else {
                xyoManufactureIdToCreator.remove(XyoBluetoothClientDeviceType.BridgeX.raw)
            }
        }

        override fun getDevicesFromScanResult(
                context: Context,
                scanResult: XYScanResult,
                globalDevices: ConcurrentHashMap<String, XYBluetoothDevice>,
                foundDevices: HashMap<String,
                        XYBluetoothDevice>
        ) {
            val hash = hashFromScanResult(scanResult)
            val createdDevice = XyoBridgeX(context, scanResult, hash)
            val foundDevice = foundDevices[hash]
            if (foundDevice != null) {
                foundDevice.rssi = scanResult.rssi
                foundDevice.updateBluetoothDevice(scanResult.device)
            } else {
                foundDevices[hash] = createdDevice
                globalDevices[hash] = createdDevice
            }
        }
    }
}