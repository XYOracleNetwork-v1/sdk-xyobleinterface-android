package network.xyo.modbluetoothkotlin.server

import android.bluetooth.*
import android.util.Log
import kotlinx.coroutines.*
import network.xyo.ble.gatt.server.XYBluetoothCharacteristic
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.gatt.server.XYBluetoothService
import network.xyo.ble.gatt.server.responders.XYStaticReadResponder
import network.xyo.modbluetoothkotlin.XyoBluetoothConnection
import network.xyo.modbluetoothkotlin.XyoPipeCreatorBase
import network.xyo.modbluetoothkotlin.XyoUuids
import network.xyo.sdkcorekotlin.network.XyoNetworkPeer
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * A BLE server that can create XYO Network pipes.
 *
 * @param bluetoothServer The Bluetooth server to create the pipe with.
 */
class XyoBluetoothServer (private val bluetoothServer : XYBluetoothGattServer) : XyoPipeCreatorBase() {
    private val responderKey = this.toString()
    override fun stop() {
        super.stop()
        bluetoothWriteCharacteristic.removeResponder(responderKey)
        logInfo("XyoBluetoothServer stopped.")
    }

    override fun start (procedureCatalogueInterface: XyoNetworkProcedureCatalogueInterface) {
        Log.v("WIN", "START SERVER")
        logInfo("XyoBluetoothServer started.")
        canCreate = true
        bluetoothWriteCharacteristic.clearWriteResponders()

        // add a responder to the characteristic to wait for a read request
        Log.v("WIN", "ADDING RESPONDER")
        bluetoothWriteCharacteristic.addResponder(responderKey, object : XYBluetoothCharacteristic.XYBluetoothWriteCharacteristicResponder {
            override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
                Log.v("WIN", "ON WRITE")
                if (writeRequestValue != null && device != null) {
                    logInfo("XyoBluetoothServer onWriteRequest!")
                    // unpack the catalogue

                    val connectionDevice = XyoBluetoothConnection()
                    onCreateConnection(connectionDevice)
                    connectionDevice.onTry()

                    val sizeOfCatalogue = writeRequestValue[0].toInt() and 0xFFFF
                    val catalogue = writeRequestValue.copyOfRange(1, sizeOfCatalogue + 1)

                    // check if the request can do the catalogue
                    if (procedureCatalogueInterface.canDo(catalogue)) {
                        Log.v("WIN", "CAN DO CREATING PIPE")
                        val pipe = XyoBluetoothServerPipe(device, bluetoothWriteCharacteristic, catalogue)
                        connectionDevice.pipe = pipe
                        connectionDevice.onCreate(pipe)
                        return true
                    }
                    Log.v("WIN", "SERVER CAN NOT DO")
                    connectionDevice.onFail()
                }

                return false
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
            Log.v("WIN", "SERVER CLOSE")
            bluetoothServer.disconnect(bluetoothDevice)
        }

        override fun send(data: ByteArray,  waitForResponse : Boolean) : Deferred<ByteArray?> {
            Log.v("WIN", "SERVER SEND")

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
                                    Log.v("WIN", "CONNECTION STATE CHANGE")
                                    bluetoothServer.removeListener(disconnectKey)
                                    cont.resume(null)
                                    coroutineContext.cancel()
                                    readValueJob.cancel()
                                    return
                                }
                            }
                        }

                        bluetoothServer.addListener(disconnectKey, listener)

                        Log.v("WIN", "AWAITING SEND")
                        val readValue = readValueJob.await()
                        bluetoothServer.removeListener(disconnectKey)
                        cont.resume(readValue)
                    }
                }
            }
        }

        private fun sendAwait (outgoingPacket: ByteArray, waitForResponse: Boolean) = GlobalScope.async {
            // make sure to set a value before notifying
            bluetoothWriteCharacteristic.value = byteArrayOf(0x00)

            // notify the characteristic has changed
            Log.v("WIN", "SEND NOTIFACTION AWAIT ")
            bluetoothServer.sendNotification(bluetoothWriteCharacteristic, false, bluetoothDevice).await()
            Log.v("WIN", "SEND NOTIFACTION AWAIT DONE")

            Log.v("WIN", "SEND PACKET SERVER")
            sendPacket(outgoingPacket, writeCharacteristic, bluetoothDevice)
            Log.v("WIN", "SEND PACKET SERVER DONE")

            if (waitForResponse) {
                Log.v("WIN", "WAITING FOR RESPONSE")
                // read packet
                val result = readPacket(writeCharacteristic, bluetoothDevice)
                Log.v("WIN", "READ RESPONSE SERVER")
                return@async result
            }

            return@async null
        }
    }

    suspend fun sendPacket (outgoingPacket : ByteArray, characteristic: XYBluetoothCharacteristic, bluetoothDevice: BluetoothDevice) = suspendCancellableCoroutine<Any?> { cont ->
        val key = "sendPacket$this ${Math.random()}"

        val responder = XYStaticReadResponder(outgoingPacket, object : XYStaticReadResponder.XYStaticReadResponderListener {
            override fun onReadComplete() {
                characteristic.removeReadResponder(key)
                cont.resume(null)
            }
        })

        characteristic.addReadResponder(key, responder)
    }

    suspend fun readPacket (writeCharacteristic: XYBluetoothCharacteristic, bluetoothDevice: BluetoothDevice) : ByteArray? = suspendCancellableCoroutine<ByteArray?> { cont ->
        val key = "readPacket$this ${Math.random()}"

        writeCharacteristic.addResponder(key, object : XYBluetoothCharacteristic.XYBluetoothWriteCharacteristicResponder {
            var buffer : ByteBuffer? = null
            var numberOfPackets  = 0
            var receivedSize = 0
            var totalSize = 0

            override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
                if (bluetoothDevice.address == device?.address) {
                    if (numberOfPackets == 0) {
                        totalSize = ByteBuffer.wrap(writeRequestValue).int
                        buffer = ByteBuffer.allocate(totalSize)
                    }

                    receivedSize += writeRequestValue?.size ?: 0
                    numberOfPackets++

                    buffer?.put(writeRequestValue)

                    if (receivedSize == totalSize) {
                        writeCharacteristic.removeResponder(key)
                        cont.resume(buffer?.array()?.copyOfRange(4, totalSize))
                    }

                    return true
                }
                return null
            }
        })
    }

    fun spinUpServer () = GlobalScope.async {
        bluetoothService.addCharacteristic(bluetoothWriteCharacteristic)
        bluetoothServer.startServer()
        return@async bluetoothServer.addService(bluetoothService).await()
    }

    companion object {
        private val bluetoothWriteCharacteristic = XYBluetoothCharacteristic(
                XyoUuids.XYO_WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or  BluetoothGattCharacteristic.PROPERTY_READ
        )


        private val bluetoothService = XYBluetoothService(
                XyoUuids.XYO_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
    }
}