package network.xyo.modbluetoothkotlin.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYCreator
import network.xyo.ble.gatt.XYBluetoothError
import network.xyo.ble.scanner.XYScanResult
import network.xyo.modbluetoothkotlin.XyoUuids
import network.xyo.modbluetoothkotlin.packet.XyoBluetoothIncomingPacket
import network.xyo.modbluetoothkotlin.packet.XyoBluetoothOutgoingPacket
import network.xyo.sdkcorekotlin.network.XyoNetworkPeer
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import network.xyo.sdkobjectmodelkotlin.objects.toHexString
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * A Bluetooth client that can create a XyoNetworkPipe. This pipe can be used with the sdk-core-kotlin to talk to
 * other XYO enabled devices.
 *
 * @property context Context of the device
 * @property device The android bluetooth device
 * @property hash The unique hash of the device
 */
class XyoBluetoothClient(context: Context, device: BluetoothDevice?, hash : Int) : XYBluetoothDevice(context, device, hash) {
    /**
     * The standard size of the MTU of the connection. This value is used when chunking large amounts of data.
     */
    private var mtu = DEFAULT_MTU



    /**
     * creates a XyoNetworkPipe with THIS bluetooth device.
     *
     * @param catalogueInterface The catalogue to respect when creating a pipe.
     * @return A Deferred XyoNetworkPipe if successful, null if not.
     */
    fun createPipe(catalogueInterface: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        return@async suspendCoroutine<XyoNetworkPipe?> { cont ->
            GlobalScope.launch {
               connection {
                    val pipe = doCreatePipe(catalogueInterface).await()

                    cont.resume(pipe)
                    coroutineContext.cancel()
                }.await()


                /**
                 * Make sure to cancel all coroutines in the scope or the suspendCoroutine may hang.
                 */
                cont.resume(null)
                coroutineContext.cancel()
                coroutineContext.cancelChildren()
                cont.context.cancelChildren()
                return@launch
            }
        }
    }



    /**
     * The logic for creating a XyoNetworkPipe
     *
     * @param catalogueInterface The catalogue to respect when creating a pipe.
     * @return A Deferred XyoNetworkPipe if successful, null if not.
     */
    private fun doCreatePipe(catalogueInterface: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        val sizeEncodedProcedureCatalogue = getSizeEncodedProcedureCatalogue(catalogueInterface)
        // writes the encoded catalogue to the server
        log.info("Writing catalogue to server.")

        findAndWriteCharacteristicNotify(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE, true).await()

        val requestMtu = requestMtu(MAX_MTU).await()

        if (requestMtu.error == null) {
            mtu = (requestMtu.value ?: mtu) - 3
        }

        val readJob = readIncommoding()
        val writeError = sendPacket(sizeEncodedProcedureCatalogue).await()

        if (writeError != null) {
            log.info("Error writing catalogue to server. $writeError")
            disconnect().await()
            close().await()
            return@async null
        }

        log.info("Wrote catalogue to server.")

        val incomingPacket = readJob.await()

        log.info("Read the server's response. ${incomingPacket?.size}")

        // check if the packet was read successfully
        if (incomingPacket != null) {
            log.info("Read the server's response (good).")
            return@async createPipeFromResponse(incomingPacket)
        } else {
            disconnect().await()
            close().await()
            log.info("Error reading the server's response.")
            return@async null
        }
    }



    /**
     * Tries to create a XyoBluetoothClientPipe after getting the response from the server.
     *
     * @param incomingPacket The response from the server.
     * @return A XyoBluetoothClientPipe from the
     */
    private fun createPipeFromResponse(incomingPacket: ByteArray): XyoBluetoothClientPipe? {

        /**
         * We and with 0xFFFF to get the unsigned value of the size.
         */
        val sizeOfCatalog = incomingPacket[0].toInt() and 0xFFFF

        if (sizeOfCatalog + 1 > incomingPacket.size) {
            return null
        }

        val catalog = incomingPacket.copyOfRange(1, sizeOfCatalog + 1)
        val initiationData = incomingPacket.copyOfRange(sizeOfCatalog + 1, incomingPacket.size)

        return XyoBluetoothClientPipe(catalog, initiationData, rssi)
    }



    /**
     * Converts a XyoNetworkProcedureCatalogueInterface to the proper byte format when sending to the server.
     *
     * @param catalogueInterface The catalogue to convert.
     * @return The byte encoded catalogue with a prepended size.
     */
    private fun getSizeEncodedProcedureCatalogue(catalogueInterface: XyoNetworkProcedureCatalogueInterface): ByteArray {
        val firstDataToSend = catalogueInterface.getEncodedCanDo()
        val buff = ByteBuffer.allocate(1 + firstDataToSend.size)
        buff.put(firstDataToSend.size.toByte())
        buff.put(firstDataToSend)
        return buff.array()
    }



    /**
     * The class that complies to the XyoNetworkPipe interface. After a connection and catalogue negation has been
     * successfully executed this class is created and returned to the createPipe() function. This class includes
     * methods for sending data back and fourth between nodes that complies to the XYO Network BLE transfer protocol.
     *
     * @property role The catalogue of the other party involved (the choice of the pipe).
     * @property initiationData The data that the other party sent after connecting (if any).
     * @property rssi The RSSI of the connection. This is used for the RSSI heuristic (if any).
     */
    inner class XyoBluetoothClientPipe(private val role: ByteArray, override val initiationData: ByteArray?, val rssi : Int?) : XyoNetworkPipe() {

        /**
         * The XyoNetworkPeer at the other end of the pipe.
         */
        override val peer: XyoNetworkPeer = object : XyoNetworkPeer() {
            override fun getRole(): ByteArray {
                return role
            }

            override fun getTemporaryPeerId(): Int {
                return device?.address?.hashCode() ?: 0
            }
        }



        /**
         * Closes the pipe between parties. In this case, disconnects from the device and closes the GATT. This should
         * be called after the pipe is finished being used.
         */
        override fun close(): Deferred<Any?> = GlobalScope.async {
            disconnect().await()
            this@XyoBluetoothClient.close().await()
        }



        /**
         * Sends data to the other end of the pipe and waits for a response if the waitForResponse flag is set to
         * true. NOTE: The send and recive are abstracted away from the caller, this means that this may not be the
         * exact bytes going over the wire.
         *
         * @param data The data to send to the other end of the pipe.
         * @param waitForResponse If this flag is set, this function will wait for a response. If not, will return
         * null.
         * @return A differed ByteArray of the response of the server. If waitForResponse is null, will return null.
         */
        override fun send(data: ByteArray, waitForResponse: Boolean): Deferred<ByteArray?> = GlobalScope.async {
            return@async suspendCoroutine<ByteArray?> { cont ->
                GlobalScope.launch {
                    val status = connection {
                        val disconnectKey = this.toString() + Math.random().toString()

                        val sendAndReceive = GlobalScope.async {

                            val readJob = readIncommoding()
                            val packetError = sendPacket(data).await()

                            log.info("Sent entire packet to the server.")
                            if (packetError == null) {
                                log.info("Sent entire packet to the server (good).")
                                var valueIn: ByteArray? = null


                                if (waitForResponse) {
                                    valueIn = readJob.await()
                                }
                                log.info("Have read entire server response packet.")
                                removeListener(disconnectKey)
                                cont.resume(valueIn)
                            } else {
                                log.info("Error sending entire packet to the server.")
                                removeListener(disconnectKey)
                                cont.resume(null)
                            }
                        }

                        // add the disconnect listener
                        log.info("Adding disconnect listener.")
                        addListener(disconnectKey, object : XYBluetoothDevice.Listener() {
                            override fun connectionStateChanged(device: XYBluetoothDevice, newState: Int) {
                                when (newState) {

                                    BluetoothGatt.STATE_DISCONNECTED -> {
                                        log.info("Someone disconnected.")

                                        if (cont.context.isActive) {
                                            log.info("Context is still active.")
                                            removeListener(disconnectKey)

                                            log.info("Canceling send and receive.")

                                            cont.resume(null)
                                            coroutineContext.cancel()
                                            sendAndReceive.cancel()
                                        }
                                    }
                                }
                            }
                        })
                    }.await()

                    if (status.error != null) {
                        cont.resume(null)
                    }
                }
            }
        }
    }



    /**
     * Writes a packet to the XYO_WRITE characteristic.
     *
     * @param outgoingPacket The packet to send to the server. This value will be chunked accordingly, if larger than
     * the MTU of the connection.
     * @return An XYBluetoothError if there was an issue writing the packet.
     */
    private fun sendPacket(outgoingPacket: ByteArray): Deferred<XYBluetoothError?> = GlobalScope.async {
        return@async suspendCoroutine<XYBluetoothError?> { cont ->
            thread {
                GlobalScope.launch {
                    val chunknedOutgoingPacket = XyoBluetoothOutgoingPacket(mtu, outgoingPacket)

                    while (chunknedOutgoingPacket.canSendNext) {
                        val test = chunknedOutgoingPacket.getNext()
                        val error = findAndWriteCharacteristic(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE, test).await().error

                        if (error != null) {
                            cont.resume(error)
                            return@launch
                        }
                    }

                    cont.resume(null)
                }
            }
        }
    }



    /**
     * Reads an incoming packet by listening for notifications. This function must be invoked before any notifications
     * are sent or else will return null. Timeout of the first notification is defined with FIRST_NOTIFY_TIMEOUT, in
     * milliseconds and notifaction delta timeout is defined as NOTIFY_TIMEOUT in milliseconds.
     *
     * @return A deferred ByteArray of the value read. If there was an error or timeout, will return null.
     */
    private fun readIncommoding()  : Deferred<ByteArray?> = GlobalScope.async {
        return@async suspendCoroutine<ByteArray?> { cont ->
            val key = this.toString() + Math.random().toString()

            addGattListener(key, object : XYBluetoothGattCallback() {
                var numberOfPackets = 0
                var hasResumed = false

                var timeoutJob : Job = GlobalScope.launch {
                    delay(FIRST_NOTIFY_TIMEOUT.toLong())
                    hasResumed = true
                    removeGattListener(key)
                    cont.resume(null)
                }

                var incomingPacket : XyoBluetoothIncomingPacket? = null

                @Synchronized
                override fun onCharacteristicChangedValue(gatt: BluetoothGatt?, characteristicToRead: BluetoothGattCharacteristic?, value: ByteArray?) {
                    super.onCharacteristicChangedValue(gatt, characteristicToRead, value)
                    Log.v("NATE", "RECIVED NOTIFY ${value?.toHexString()}")

                    if (characteristicToRead?.uuid == XyoUuids.XYO_WRITE && !hasResumed) {

                        if (numberOfPackets == 0 && value != null) {
                            incomingPacket = XyoBluetoothIncomingPacket(value)
                        } else if (value != null ){
                            incomingPacket?.addPacket(value)
                        }

                        if (incomingPacket?.done == true) {
                            hasResumed = true
                            removeGattListener(key)
                            timeoutJob.cancel()
                            cont.resume(incomingPacket?.getCurrentBuffer())
                        } else {
                            timeoutJob.cancel()
                            timeoutJob = GlobalScope.launch {
                                delay(NOTIFY_TIMEOUT.toLong())
                                hasResumed = true
                                removeGattListener(key)
                                cont.resume(null)
                            }
                        }

                        numberOfPackets++
                    }
                }
            })
        }
    }



    companion object : XYCreator() {
        const val FIRST_NOTIFY_TIMEOUT = 10_000
        const val NOTIFY_TIMEOUT = 1_500
        const val MAX_MTU = 512
        const val DEFAULT_MTU = 23

        /**
         * Enable this device to be created on scan.
         *
         * @param enable Weather or not to enable the device.
         */
        fun enable(enable: Boolean) {
            if (enable) {
                serviceToCreator[XyoUuids.XYO_SERVICE] = this
            } else {
                serviceToCreator.remove(XyoUuids.XYO_SERVICE)
            }
        }



        override fun getDevicesFromScanResult(context: Context, scanResult: XYScanResult, globalDevices: ConcurrentHashMap<Int, XYBluetoothDevice>, foundDevices: HashMap<Int, XYBluetoothDevice>) {
            val device = scanResult.device

            /**
             * Get the device address to make sure you don't connect to yourself
             */
            val bluetoothManager = context.applicationContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter
            val isAXyoDevice = scanResult.scanRecord?.serviceUuids?.contains(ParcelUuid(XyoUuids.XYO_SERVICE))

            if (device != null && isAXyoDevice == true && bluetoothAdapter?.address != device.address) {
                val hash = scanResult.scanRecord?.getManufacturerSpecificData(13)?.contentHashCode() ?: device.address.hashCode()

                if (!foundDevices.containsKey(hash) && !globalDevices.contains(hash)) {
                    val createdDevice = XyoBluetoothClient(context, device, hash)

                    foundDevices[hash] = createdDevice
                    globalDevices[hash] = createdDevice
                }
            }
        }
    }
}