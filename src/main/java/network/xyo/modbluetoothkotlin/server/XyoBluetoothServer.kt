package network.xyo.modbluetoothkotlin.server

import android.bluetooth.*
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
        logInfo("XyoBluetoothServer started.")
        canCreate = true
        bluetoothReadCharacteristic.clearReadResponders()
        bluetoothWriteCharacteristic.clearWriteResponders()

        // add a responder to the characteristic to wait for a read request
        bluetoothWriteCharacteristic.addResponder(responderKey, object : XYBluetoothCharacteristic.XYBluetoothWriteCharacteristicResponder {
            override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
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
                        val pipe = XyoBluetoothServerPipe(device, bluetoothReadCharacteristic, bluetoothWriteCharacteristic, catalogue)
                        connectionDevice.pipe = pipe
                        connectionDevice.onCreate(pipe)
                        return true
                    }
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
                                       private val readCharacteristic: XYBluetoothCharacteristic,
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
                    GlobalScope.async {
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
            bluetoothReadCharacteristic.value = byteArrayOf(0x00)

            // notify the characteristic has changed
            bluetoothServer.sendNotification(bluetoothReadCharacteristic, false, bluetoothDevice).await()

            sendPacket(outgoingPacket, readCharacteristic, bluetoothDevice)

            if (waitForResponse) {
                // read packet
                return@async readPacket(writeCharacteristic, bluetoothDevice)
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
        bluetoothService.addCharacteristic(bluetoothReadCharacteristic)
        bluetoothServer.startServer()
        return@async bluetoothServer.addService(bluetoothService).await()
    }

    companion object {
        private val bluetoothWriteCharacteristic = XYBluetoothCharacteristic(
                XyoUuids.XYO_WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        private val bluetoothReadCharacteristic = XYBluetoothCharacteristic(
                XyoUuids.XYO_READ,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PROPERTY_READ

        )

        private val bluetoothService = XYBluetoothService(
                XyoUuids.XYO_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
    }
}