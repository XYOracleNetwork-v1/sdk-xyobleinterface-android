package network.xyo.mod_bluetooth_kotlin

import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.scanner.XYFilteredSmartScan
import network.xyo.ble.scanner.XYFilteredSmartScanModern
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface

/**
 * Helps manage creating a XyoBluetoothClient that can create a pipe.
 *
 * @param scanner The scanner to get devices from.
 */
class XyoBluetoothClientCreator(private val scanner: XYFilteredSmartScanModern) : XyoPipeCreatorBase() {
    private val scannerKey = "scanner$this"
    private var gettingDevice = false

    override fun stop() {
        super.stop()
        scanner.removeListener(scannerKey)
        logInfo("XyoBluetoothClientCreator stopped.")
    }

    override fun start(procedureCatalogueInterface: XyoNetworkProcedureCatalogueInterface) {
        logInfo("XyoBluetoothClientCreator started.")
        canCreate = true
        gettingDevice = false

        val scannerCallback = object : XYFilteredSmartScan.Listener() {
            override fun detected(device: XYBluetoothDevice) {
                super.detected(device)

                if (!gettingDevice) {
                    logInfo(gettingDevice.toString())
                    checkDevice(device, procedureCatalogueInterface)
                }

            }
        }

        scanner.addListener(scannerKey, scannerCallback)
    }

    private fun getClientFromNearby(procedureCatalogue: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        logInfo("getClientFromNearby.")
        for (device in scanner.devices.values.toTypedArray()) {
            val newPipe = checkDevice(device, procedureCatalogue).await()
            if (newPipe != null) {
                return@async newPipe
            }

            delay(CONNECTION_DELAY)
        }

        return@async null
    }

    private fun checkDevice(device: XYBluetoothDevice, procedureCatalogue: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        gettingDevice = true
        logInfo("Trying device : ${device.address}")
        if (device is XyoBluetoothClient) {
            logInfo("Device is XyoBluetoothClient : ${device.address}")
            val connectionDevice = XyoBluetoothConnection()
            onCreateConnection(connectionDevice)

            connectionDevice.onTry()
            val pipe = device.createPipe(procedureCatalogue).await()

            if (pipe != null) {
                logInfo("Created pipe : ${device.address}")
                connectionDevice.pipe = pipe
                connectionDevice.onCreate(pipe)
                return@async pipe
            }

            logInfo("Could not create pipe : ${device.address}")
            connectionDevice.onFail()
        } else {
            logInfo("Device is not XyoBluetoothClient : ${device.address}")
            gettingDevice = false
            return@async null
        }

        gettingDevice = false
        return@async null
    }

    init {
        XyoBluetoothClient.enable(true)
    }

    companion object {
        val CONNECTION_DELAY: Int
            get() = (Math.random() * 3_000).toInt()
    }
}