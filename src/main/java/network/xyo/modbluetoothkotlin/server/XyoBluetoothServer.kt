package network.xyo.modbluetoothkotlin.server

import android.bluetooth.*
import android.util.Log
import kotlinx.coroutines.*
import network.xyo.ble.gatt.server.XYBluetoothCharacteristic
import network.xyo.ble.gatt.server.XYBluetoothDescriptor
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.gatt.server.XYBluetoothService
import network.xyo.ble.gatt.server.responders.XYBluetoothReadResponder
import network.xyo.ble.gatt.server.responders.XYBluetoothWriteResponder
import network.xyo.modbluetoothkotlin.XyoBluetoothConnection
import network.xyo.modbluetoothkotlin.XyoPipeCreatorBase
import network.xyo.modbluetoothkotlin.XyoUuids
import network.xyo.modbluetoothkotlin.XyoUuids.NOTIFY_DESCREPTOR
import network.xyo.modbluetoothkotlin.packet.XyoBluetoothIncomingPacket
import network.xyo.modbluetoothkotlin.packet.XyoBluetoothOutgoingPacket
import network.xyo.sdkcorekotlin.network.XyoNetworkPeer
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import kotlin.coroutines.resume

/**
 * A BLE server that can create XYO Network pipes.
 *
 * @param bluetoothServer The Bluetooth server to create the pipe with.
 */
class XyoBluetoothServer (private val bluetoothServer : XYBluetoothGattServer) : XyoPipeCreatorBase() {
    private val responderKey = this.toString()
    private val MTUs = HashMap<Int, Int>()

    override fun stop() {
        super.stop()
        bluetoothWriteCharacteristic.removeResponder(responderKey)
        logInfo("XyoBluetoothServer stopped.")
    }

    override fun start (procedureCatalogueInterface: XyoNetworkProcedureCatalogueInterface) {
        logInfo("XyoBluetoothServer started.")
        canCreate = true
        bluetoothWriteCharacteristic.clearWriteResponders()
        val key = "serverkey"

        // add a responder to the characteristic to wait for a read request

        bluetoothServer.addListener(key, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)

                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {

                        GlobalScope.launch {
                            val incomming = readPacket(bluetoothWriteCharacteristic, null)

                            if (incomming != null && device != null) {
                                val connectionDevice = XyoBluetoothConnection()
                                onCreateConnection(connectionDevice)
                                connectionDevice.onTry()

                                val sizeOfCatalogue = incomming[0].toInt() and 0xFFFF
                                val catalogue = incomming.copyOfRange(1, sizeOfCatalogue + 1)

                                // check if the request can do the catalogue
                                if (procedureCatalogueInterface.canDo(catalogue)) {
                                    val pipe = XyoBluetoothServerPipe(device, bluetoothWriteCharacteristic, catalogue)
                                    connectionDevice.pipe = pipe
                                    connectionDevice.onCreate(pipe)

                                    return@launch
                                }
                                connectionDevice.onFail()
                            }
                        }
                    }
                }
            }
        })

    }

    /**
     * This pipe will be creating after a negotiation has occurred.
     */
    inner class XyoBluetoothServerPipe(private val bluetoothDevice: BluetoothDevice,
                                       private val writeCharacteristic: XYBluetoothCharacteristic,
                                       private val catalogue: ByteArray) : XyoNetworkPipe() {

        override val initiationData: ByteArray? = null
        override val peer: XyoNetworkPeer = object : XyoNetworkPeer() {
            override fun getRole(): ByteArray {
                return catalogue
            }

            override fun getTemporaryPeerId(): Int {
                return bluetoothDevice.address.hashCode()
            }
        }

        override fun close(): Deferred<Any?> = GlobalScope.async {
            bluetoothServer.disconnect(bluetoothDevice)
        }

        override fun send(data: ByteArray,  waitForResponse : Boolean) : Deferred<ByteArray?> {

            return GlobalScope.async {
                if (!bluetoothServer.isDeviceConnected(bluetoothDevice)) {
                    return@async null
                }

                val disconnectKey = "${this}disconnect"

                return@async suspendCancellableCoroutine <ByteArray?> { cont ->
                    // send a packet
                    GlobalScope.launch {

                        val readValueJob = sendAwait(data, waitForResponse)

                        val listener = object : BluetoothGattServerCallback() {
                            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                                if (cont.isActive && newState == BluetoothGatt.STATE_DISCONNECTED && device?.address == bluetoothDevice.address) {
                                    bluetoothServer.removeListener(disconnectKey)
                                    cont.resume(null)
                                    coroutineContext.cancel()
                                    readValueJob.cancel()
                                    return
                                }
                            }
                        }

                        bluetoothServer.addListener(disconnectKey, listener)

                        val readValue = readValueJob.await()
                        bluetoothServer.removeListener(disconnectKey)
                        cont.resume(readValue)
                    }
                }
            }
        }

        private fun sendAwait (outgoingPacket: ByteArray, waitForResponse: Boolean) = GlobalScope.async {
            // make sure to set a value before notifying

            sendPacket(outgoingPacket, writeCharacteristic, bluetoothDevice)

            if (waitForResponse) {
                // read packet
                return@async readPacket(writeCharacteristic, bluetoothDevice)
            }

            return@async null
        }
    }

    suspend fun sendPacket (outgoingPacket : ByteArray, characteristic: XYBluetoothCharacteristic, bluetoothDevice: BluetoothDevice) = suspendCancellableCoroutine<Any?> { cont ->
        val key = "sendPacket$this ${Math.random()}"
        characteristic.value = outgoingPacket

        val timeoutResume = GlobalScope.launch {
            delay(10_000)
            characteristic.removeReadResponder(key)
            cont.resume(null)
        }

        val outgoingChuckedPacket = XyoBluetoothOutgoingPacket((MTUs[bluetoothDevice.hashCode()] ?: 24) - 4, outgoingPacket)


        GlobalScope.launch {
            while (outgoingChuckedPacket.canSendNext) {
                characteristic.value = outgoingChuckedPacket.getNext()
                delay(1_00)
                bluetoothServer.sendNotification(bluetoothWriteCharacteristic, true, bluetoothDevice).await()

                if (!outgoingChuckedPacket.canSendNext) {
                    timeoutResume.cancel()
                    cont.resume(null)
                }
            }
        }
    }

    suspend fun readPacket (writeCharacteristic: XYBluetoothCharacteristic, bluetoothDevice: BluetoothDevice?) : ByteArray? = suspendCancellableCoroutine<ByteArray?> { cont ->
        val key = "readPacket$this ${Math.random()}"

        val timeoutResume = GlobalScope.launch {
            delay(10_000)
            writeCharacteristic.removeResponder(key)
            cont.resume(null)
        }


        writeCharacteristic.addWriteResponder(key, object : XYBluetoothWriteResponder {
            var numberOfPackets  = 0
            var incomingPacket : XyoBluetoothIncomingPacket? = null

            override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
                if (bluetoothDevice == null || bluetoothDevice.address == device?.address) {
                    if (numberOfPackets == 0 && writeRequestValue != null) {
                        incomingPacket = XyoBluetoothIncomingPacket(writeRequestValue)
                    } else if (writeRequestValue != null){
                        val finalPacket = incomingPacket?.addPacket(writeRequestValue)

                        if (finalPacket != null) {
                            writeCharacteristic.removeResponder(key)
                            timeoutResume.cancel()
                            cont.resume(finalPacket)
                        }
                    }

                    numberOfPackets++
                    return true
                }

                return null
            }
        })
    }


    fun spinUpServer () = GlobalScope.async {
        bluetoothWriteCharacteristic.addDescriptor(notifyDescriptor)
        bluetoothService.addCharacteristic(bluetoothWriteCharacteristic)
        bluetoothServer.startServer()
        bluetoothWriteCharacteristic.writeType = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        return@async bluetoothServer.addService(bluetoothService).await( )
    }

    init {
        bluetoothServer.addListener("main$this", object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)

                when (newState) {
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        MTUs.remove(device.hashCode())
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                super.onMtuChanged(device, mtu)

                MTUs[device.hashCode()] = mtu
            }
        })
    }

    companion object {
        private val notifyDescriptor = object : XYBluetoothDescriptor(NOTIFY_DESCREPTOR, BluetoothGattDescriptor.PERMISSION_WRITE) {
            override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
                return true
            }
        }

        private val bluetoothWriteCharacteristic = XYBluetoothCharacteristic(
                XyoUuids.XYO_WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE ,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        private val bluetoothService = XYBluetoothService(
                XyoUuids.XYO_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
    }
}