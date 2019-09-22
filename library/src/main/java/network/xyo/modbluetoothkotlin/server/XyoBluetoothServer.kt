package network.xyo.modbluetoothkotlin.server

import android.bluetooth.*
import android.util.SparseIntArray
import kotlinx.coroutines.*
import network.xyo.ble.gatt.peripheral.XYBluetoothError
import network.xyo.ble.gatt.peripheral.XYBluetoothResult
import network.xyo.ble.gatt.server.XYBluetoothCharacteristic
import network.xyo.ble.gatt.server.XYBluetoothDescriptor
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.gatt.server.XYBluetoothService
import network.xyo.ble.gatt.server.responders.XYBluetoothWriteResponder
import network.xyo.modbluetoothkotlin.XyoUuids
import network.xyo.modbluetoothkotlin.XyoUuids.NOTIFY_DESCRIPTOR
import network.xyo.modbluetoothkotlin.packet.XyoBluetoothIncomingPacket
import network.xyo.modbluetoothkotlin.packet.XyoBluetoothOutgoingPacket
import network.xyo.sdkcorekotlin.network.XyoAdvertisePacket
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkobjectmodelkotlin.structure.XyoObjectStructure

/**
 * A BLE GATT Server than can create XyoNetworkPipes. This pipe can be used with the sdk-core-kotlin to talk to
 * other XYO enabled devices.
 *
 * @property bluetoothServer The Bluetooth GATT server to create the pipe with.
 */
class XyoBluetoothServer(private val bluetoothServer: XYBluetoothGattServer) {

    var listener: Listener? = null

    interface Listener {
        fun onPipe(pipe: XyoNetworkPipe)
    }

    /**
     * The key of the main gatt listener to listen for new new XYO Devices.
     */
    private val responderKey = this.toString()


    /**
     * A list of MTU values to device hash codes.
     *
     * mtuS[DEVICE HASH CODE] = MTU of DEVICE
     */
    private val mtuS = SparseIntArray()


    private val serverPrimaryEndpoint = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)


            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    GlobalScope.launch {
                        val incoming = readPacket(bluetoothWriteCharacteristic, device)

                        if (incoming != null && device != null) {
                            val pipe = XyoBluetoothServerPipe(device, bluetoothWriteCharacteristic, incoming)
                            listener?.onPipe(pipe)
                        }
                    }
                }
            }
        }
    }


    /**
     * This pipe will be creating after a negotiation has occurred. This pipe abstracts a BLE Gatt Server than can
     * talk to a another BLE gatt client enabled device.
     *
     * @property bluetoothDevice The device that the server is connected to. This is used to filter request from
     * other devices.
     * @property writeCharacteristic The characteristic to write look for writes from the server.
     * @property catalog The catalog that the client has sent to the server on connection.
     */
    inner class XyoBluetoothServerPipe(private val bluetoothDevice: BluetoothDevice,
                                       private val writeCharacteristic: XYBluetoothCharacteristic,
                                       startingData: ByteArray) : XyoNetworkPipe {

        /**
         * The data that the connection was tarted with. This value is set to null since there is no ignition data
         * on the first write from the client.
         */
        override val initiationData: XyoAdvertisePacket? = XyoAdvertisePacket(startingData)


        /**
         * Closes the pipe. In this case, tells the BLE GATT server to shut disconnect from the device.
         */
        override fun close(): Deferred<Any?> = GlobalScope.async {
            bluetoothServer.disconnect(bluetoothDevice)
        }

        // TODO find way to get RSSI here
        override fun getNetworkHeretics(): Array<XyoObjectStructure> {
            return arrayOf()
        }


        /**
         * Sends data to the other end of the pipe, in this case a BLE central/client. This function wraps sendAwait
         * with device connection functionality (e.g. listening for disconnects).
         *
         * @param data The data to send at the other end of the pipe.
         * @param waitForResponse If set to true, will wait for a response after sending the data.
         * @return The differed response from the party at the other end of the pipe. If waitForResponse is set to
         * true. The method will return null. Will also return null if there is an error.
         */
        override fun send(data: ByteArray, waitForResponse: Boolean): Deferred<ByteArray?> {
            return GlobalScope.async {
                if (!bluetoothServer.isDeviceConnected(bluetoothDevice)) {
                    return@async null
                }

                val disconnectKey = "$this disconnect"

                return@async suspendCancellableCoroutine<ByteArray?> { cont ->
                    GlobalScope.launch {
                        val readValueJob = sendAwaitAsync(data, waitForResponse)

                        val listener = object : BluetoothGattServerCallback() {
                            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                                if (cont.isActive && newState == BluetoothGatt.STATE_DISCONNECTED && device?.address == bluetoothDevice.address) {
                                    bluetoothServer.removeListener(disconnectKey)
                                    val idempotent = cont.tryResume(null)
                                    idempotent?.let {
                                        cont.completeResume(it)
                                    }
                                    coroutineContext.cancel()
                                    readValueJob.cancel()
                                    return
                                }
                            }
                        }

                        bluetoothServer.addListener(disconnectKey, listener)

                        val readValue = readValueJob.await()
                        bluetoothServer.removeListener(disconnectKey)

                        val idempotent = cont.tryResume(readValue)
                        idempotent?.let {
                            cont.completeResume(it)
                        }
                    }
                }
            }
        }


        /**
         * Sends data to the other end of the pipe, in this case a BLE central/client. NOTE: This function does not
         * check to see if the device is connected or listen for disconnects. That is handled in the send() function.
         *
         * @param outgoingPacket The data to send at the other end of the pipe.
         * @param waitForResponse If set to true, will wait for a response after sending the data.
         * @return The differed response from the party at the other end of the pipe. If waitForResponse is set to
         * true. The method will return null. Will also return null if there is an error.
         */
        private fun sendAwaitAsync(outgoingPacket: ByteArray, waitForResponse: Boolean) = GlobalScope.async {
            val readJob = if (waitForResponse) {
                readPacket(writeCharacteristic, bluetoothDevice)
            } else {
                null
            }

            sendPacket(outgoingPacket, writeCharacteristic, bluetoothDevice)

            if (waitForResponse) {
                return@async readJob
            }

            return@async null
        }
    }


    /**
     * Sends a packet to the central by sending notifications one after each other. The notifications will chunk the
     * data accordingly at the size of the MTU.
     *
     * @param outgoingPacket The packet to send to the other end of the pipe (BLE Central)
     * @param characteristic The characteristic to notify that has changed.
     * @param bluetoothDevice The bluetooth device to send the data to.
     */
    private suspend fun sendPacket(outgoingPacket: ByteArray, characteristic: XYBluetoothCharacteristic, bluetoothDevice: BluetoothDevice) = suspendCancellableCoroutine<XYBluetoothError?> { cont ->
        val key = "sendPacket $this ${Math.random()}"
        characteristic.value = outgoingPacket

        val timeoutResume = GlobalScope.launch {
            delay(READ_TIMEOUT.toLong())
            characteristic.removeReadResponder(key)

            val idempotent = cont.tryResume(null)
            idempotent?.let {
                cont.completeResume(it)
            }
        }

        val outgoingChuckedPacket = XyoBluetoothOutgoingPacket((mtuS[bluetoothDevice.hashCode()]) - 4, outgoingPacket, 4)


        GlobalScope.launch {
            while (outgoingChuckedPacket.canSendNext) {
                val next = outgoingChuckedPacket.getNext()
                characteristic.value = next
                delay(ADVERTISEMENT_DELTA_TIMEOUT.toLong())

                if (bluetoothServer.sendNotification(bluetoothWriteCharacteristic, true, bluetoothDevice).await()?.value != 0) {
                    timeoutResume.cancel()
                    val idempotent = cont.tryResume(null)
                    idempotent?.let {
                        cont.completeResume(it)
                    }
                }

                if (!outgoingChuckedPacket.canSendNext) {
                    timeoutResume.cancel()
                    val idempotent = cont.tryResume(null)
                    idempotent?.let {
                        cont.completeResume(it)
                    }
                }
            }
        }
    }


    /**
     * Reads an entire packet from the client. This is done by having the client write to a characteristic. The
     * timeout value for the first write is READ_TIMEOUT.
     *
     * @param writeCharacteristic The characteristic to read from
     * @param bluetoothDevice The bluetooth device to read from.
     * @return The deferred value of the read. If the request timed out, the method will return null. If there was
     * an error reading, will return null.
     */
    private suspend fun readPacket(writeCharacteristic: XYBluetoothCharacteristic, bluetoothDevice: BluetoothDevice?): ByteArray? = GlobalScope.async {
        return@async suspendCancellableCoroutine<ByteArray?> { cont ->
            val key = "readPacket$this ${Math.random()}"

            val timeoutResume = GlobalScope.launch {
                delay(READ_TIMEOUT.toLong())
                writeCharacteristic.removeResponder(key)
                val idempotent = cont.tryResume(null)
                idempotent?.let {
                    cont.completeResume(it)
                }
            }

            writeCharacteristic.addWriteResponder(key, object : XYBluetoothWriteResponder {
                var numberOfPackets = 0
                var incomingPacket: XyoBluetoothIncomingPacket? = null

                override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
                    if (bluetoothDevice == null || bluetoothDevice.address == device?.address) {
                        if (numberOfPackets == 0 && writeRequestValue != null) {
                            incomingPacket = XyoBluetoothIncomingPacket(writeRequestValue)

                            if (incomingPacket?.done == true) {
                                writeCharacteristic.removeResponder(key)
                                timeoutResume.cancel()
                                val idempotent = cont.tryResume(incomingPacket?.getCurrentBuffer())
                                idempotent?.let {
                                    cont.completeResume(it)
                                }
                            }
                        } else if (writeRequestValue != null) {
                            val finalPacket = incomingPacket?.addPacket(writeRequestValue)

                            if (finalPacket != null) {
                                writeCharacteristic.removeResponder(key)
                                timeoutResume.cancel()

                                val idempotent = cont.tryResume(finalPacket)
                                idempotent?.let {
                                    cont.completeResume(it)
                                }
                            }
                        }

                        numberOfPackets++
                        return true
                    }

                    return null
                }
            })
        }
    }.await()


    /**
     * Starts up the server so it can be connected to. NOTE: this does not start advertising. Advertising is
     * handled outside of the scope of this function.
     *
     * @return A deferred XYGattStatus with the status of the service being added.
     */
    suspend fun initServer(): XYBluetoothResult<Int>? = GlobalScope.async {
        bluetoothWriteCharacteristic.addDescriptor(notifyDescriptor)
        bluetoothService.addCharacteristic(bluetoothWriteCharacteristic)
        bluetoothServer.startServer()

        return@async bluetoothServer.addService(bluetoothService).await()
    }.await()

    /**
     * Listens for MTU changes and updates the device to MTU map accordingly.
     */
    private val mtuListener = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)

            when (newState) {
                BluetoothGatt.STATE_DISCONNECTED -> {
                    mtuS.delete(device.hashCode())
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)

            mtuS.put(device.hashCode(), mtu)
        }
    }


    init {
        bluetoothServer.addListener("main$this", mtuListener)
        bluetoothServer.addListener(responderKey, serverPrimaryEndpoint)
    }

    companion object {
        const val READ_TIMEOUT = 12_000
        const val ADVERTISEMENT_DELTA_TIMEOUT = 100

        private val notifyDescriptor = object : XYBluetoothDescriptor(NOTIFY_DESCRIPTOR, PERMISSION_WRITE or PERMISSION_READ) {
            override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
                return true
            }

            override fun onReadRequest(device: BluetoothDevice?, offset: Int): XYBluetoothGattServer.XYReadRequest? {
                return XYBluetoothGattServer.XYReadRequest(byteArrayOf(0x00, 0x00), 0)
            }

        }

        private val bluetoothWriteCharacteristic = XYBluetoothCharacteristic(
                XyoUuids.XYO_PIPE,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        private val bluetoothService = XYBluetoothService(
                XyoUuids.XYO_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
    }
}