package network.xyo.modbluetoothkotlin.client

import android.bluetooth.BluetoothGatt
import kotlinx.coroutines.*
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.scanner.XYFilteredSmartScan
import network.xyo.ble.scanner.XYFilteredSmartScanModern
import network.xyo.modbluetoothkotlin.XyoBluetoothConnection
import network.xyo.modbluetoothkotlin.XyoPipeCreatorBase
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Helps manage creating a XyoBluetoothClient that can create a pipe.
 *
 * @param scanner The scanner to get devices from.
 */
class XyoBluetoothClientCreator(private val scanner: XYFilteredSmartScanModern) : XyoPipeCreatorBase() {
    private val clients = ConcurrentHashMap<Int, XyoBluetoothClient>()
    private var gettingDevice = false
    private var finderJob : Job? = null
    private var lastDevice : Int? = null

    override fun stop() {
        super.stop()
        finderJob?.cancel()
        logInfo("XyoBluetoothClientCreator stopped.")
    }

    override fun start(procedureCatalogueInterface: XyoNetworkProcedureCatalogueInterface) {
        logInfo("XyoBluetoothClientCreator started.")
        canCreate = true
        gettingDevice = false

        GlobalScope.launch {
            while (canCreate) {
                finderJob = getNextDevice(procedureCatalogueInterface)
                delay(SCAN_FREQUENCY.toLong())
            }
        }
    }

    private fun getNextDevice (procedureCatalogue: XyoNetworkProcedureCatalogueInterface) = GlobalScope.launch {
        val device = getRandomDevice()

        if (device != null) {
            val connectionDevice = XyoBluetoothConnection()
            onCreateConnection(connectionDevice)
            connectionDevice.onTry()

            if (!gettingDevice && canCreate) {
                val pipe = checkDevice(device, procedureCatalogue).await()

                if (pipe != null) {
                    connectionDevice.pipe = pipe
                    connectionDevice.onCreate(pipe)
                    lastDevice = device.hashCode()
                    return@launch
                }
                connectionDevice.onFail()
            }
        }
    }

    private fun getRandomDevice () : XyoBluetoothClient? {
        val randomClients = clients.values.shuffled()

        if (randomClients.isNotEmpty()) {
            val randomClient = randomClients.first()

            if (randomClient.hashCode() == lastDevice && randomClients.size > 1) {
                return getRandomDevice()
            }
            return randomClient
        }

        return null
    }

    private fun checkDevice(device: XyoBluetoothClient, procedureCatalogue: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        gettingDevice = true
        logInfo("Device is XyoBluetoothClient : ${device.address}")

        val pipe = device.createPipe(procedureCatalogue).await()

        if (pipe != null) {
            logInfo("Created pipe : ${device.address}")
            // gettingDevice = false
            return@async pipe
        }

        logInfo("Could not create pipe : ${device.address}")
        clients.remove(device.hashCode())
        logInfo("Device is not XyoBluetoothClient : ${device.address}")
        gettingDevice = false
        return@async null
    }

    private val scannerCallback = object : XYFilteredSmartScan.Listener() {
        override fun detected(device: XYBluetoothDevice) {
            super.entered(device)

            if (device is XyoBluetoothClient) {
                if (!clients.containsKey(device.hashCode()) && canCreate) {
                    clients[device.hashCode()] = device
                }
            }
        }

        override fun exited(device: XYBluetoothDevice) {
            super.exited(device)
            if (device is XyoBluetoothClient) {
                clients.remove(device.hashCode())
            }
        }
    }

    init {
        XyoBluetoothClient.enable(true)
        scanner.addListener(this.toString(), scannerCallback)
    }

    companion object {
        /**
         * How often to look aground for nearby devices
         */
        const val SCAN_FREQUENCY = 500
    }
}