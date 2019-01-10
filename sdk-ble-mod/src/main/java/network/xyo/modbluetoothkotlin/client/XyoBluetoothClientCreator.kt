package network.xyo.modbluetoothkotlin.client

import android.bluetooth.BluetoothGatt
import kotlinx.coroutines.*
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.scanner.XYSmartScan
import network.xyo.ble.scanner.XYSmartScanModern
import network.xyo.modbluetoothkotlin.XyoBluetoothConnection
import network.xyo.modbluetoothkotlin.XyoPipeCreatorBase
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Helps manage creating a XyoBluetoothClient that can create a pipe. This is used so a single function can be
 * called to get a pipe from ANY device.
 *
 * @property scanner The scanner to obtain devices from.
 */
class XyoBluetoothClientCreator(private val scanner: XYSmartScanModern) : XyoPipeCreatorBase() {

    /**
     * A map of all XyoBluetoothClient nearby.
     *
     * clients[DEVICE HASHCODE] = XyoBluetoothClient
     */
    val clients = ConcurrentHashMap<Int, XyoBluetoothClient>()


    /**
     * If we are currently trying to get a pipe with a device.
     */
    private var gettingDevice = false


    /**
     * The current job trying to create a pipe with a bluetooth device.
     */
    private var finderJob : Job? = null


    /**
     * The hashcode of the last device, this is stored so we do not connect to the same device in a row if there
     * is more than one device aground.
     */
    private var lastDevice : Int? = null


    /**
     * Stops the client creator from creating devices. This also stops the current job from creating (disconnects).
     */
    override fun stop() {
        super.stop()
        finderJob?.cancel()
        log.info("XyoBluetoothClientCreator stopped.")
    }


    /**
     * Allows the client creator to start creating pipes.
     *
     * @param procedureCatalogueInterface The catalogue to
     */
    override fun start(procedureCatalogueInterface: XyoNetworkProcedureCatalogueInterface) {
        log.info("XyoBluetoothClientCreator started.")
        canCreate = true
        gettingDevice = false

        thread {
            GlobalScope.launch {
                while (canCreate) {
                    finderJob = getNextDevice(procedureCatalogueInterface)
                    delay(SCAN_FREQUENCY.toLong())
                }
            }
        }
    }


    /**
     * Gets a device from nearby and tries to create a pipe.
     *
     * @param procedureCatalogue The catalogue to respect when creating a pipe.
     */
    private fun getNextDevice (procedureCatalogue: XyoNetworkProcedureCatalogueInterface) = GlobalScope.launch {
        val device = getRandomDevice()

        if (device != null) {
            if (!gettingDevice && canCreate) {
                val connectionDevice = XyoBluetoothConnection()
                onCreateConnection(connectionDevice)
                connectionDevice.onTry()

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


    /**
     * Gets a random device from the clients map. This will prioritize devices that are not of the last connection.
     *
     * @return A random XyoBluetoothClient. Will return null if there are no devices nearby.
     */
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


    /**
     * Checks a device and tries to create a pipe.
     *
     * @param device The device to connect and try to create a pipe with.
     * @param procedureCatalogue The catalogue to respect when creating the pipe
     * @return A deferred XyoNetworkPipe if the pipe creation was successful. If not, will return null.
     */
    private fun checkDevice(device: XyoBluetoothClient, procedureCatalogue: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        gettingDevice = true
        log.info("Device is XyoBluetoothClient : ${device.address}")

        val pipe = device.createPipe(procedureCatalogue).await()

        if (pipe != null) {
            log.info("Created pipe : ${device.address}")
            // gettingDevice = false
            return@async pipe
        }

        log.info("Could not create pipe : ${device.address}")
        clients.remove(device.hashCode())
        log.info("Device is not XyoBluetoothClient : ${device.address}")
        gettingDevice = false
        return@async null
    }


    /**
     * The scanner callback to maintain a list of nearby devices. On the event "entered", the device will be added
     * to the map. The device will be removed from the map on the "exited" event.
     */
    private val scannerCallback = object : XYSmartScan.Listener() {
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
        /**
         * Enable the device to be created by the scanner
         */
        XyoBluetoothClient.enable(true)

        /**
         * Add the listener to the scanner to maintain a map of nearby devices.
         */
        scanner.addListener(this.toString(), scannerCallback)

        /**
         * Add all of the current nearby devices to the map of nearby devices.
         */
        for ((_, device) in scanner.devices) {
            if (device is XyoBluetoothClient) {
                clients[device.hashCode()] = device
            }
        }
    }



    companion object {
        /**
         * How often to look aground for nearby devices
         */
        const val SCAN_FREQUENCY = 500
    }
}