package network.xyo.mod_bluetooth_kotlin

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.gatt.server.XYBluetoothReadCharacteristic
import network.xyo.ble.gatt.server.XYBluetoothService
import network.xyo.ble.gatt.server.XYBluetoothWriteCharacteristic
import network.xyo.sdkcorekotlin.data.XyoUnsignedHelper
import network.xyo.sdkcorekotlin.network.XyoNetworkPeer
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
        bluetoothReadCharacteristic.clearResponders()
        bluetoothWriteCharacteristic.clearResponders()

        // add a responder to the characteristic to wait for a read request
        bluetoothWriteCharacteristic.addResponder(responderKey, object : XYBluetoothWriteCharacteristic.XYBluetoothWriteCharacteristicResponder {
            override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
                if (writeRequestValue != null && device != null) {
                    logInfo("XyoBluetoothServer onWriteRequest!")
                    // unpack the catalogue

                    val connectionDevice = XyoBluetoothConnection()
                    onCreateConnection(connectionDevice)
                    connectionDevice.onTry()

                    val sizeOfCatalogue = XyoUnsignedHelper.readUnsignedByte(byteArrayOf(writeRequestValue[0]))
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
                                       private val readCharacteristic: XYBluetoothReadCharacteristic,
                                       private val writeCharacteristic: XYBluetoothWriteCharacteristic,
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
                // check if the device is still connected
                if (!bluetoothServer.isDeviceConnected(bluetoothDevice)) {
                    return@async null
                }

                val outgoingPacket = XyoBluetoothOutgoingPacket(20, data)
                val disconnectKey = "${this}disconnect"

                return@async suspendCoroutine <ByteArray?> { cont ->
                    // send a packet
                    GlobalScope.async {
                        val readValueJob = sendAwait(outgoingPacket, waitForResponse)

                        val listener = object : BluetoothGattServerCallback() {
                            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                                if (newState == BluetoothGatt.STATE_DISCONNECTED && device?.address == bluetoothDevice.address) {
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

        private fun sendAwait (outgoingPacket: XyoBluetoothOutgoingPacket, waitForResponse: Boolean) = GlobalScope.async {
            sendPacket(outgoingPacket, readCharacteristic, bluetoothDevice)

            if (waitForResponse) {
                // read packet
                return@async readPacket(writeCharacteristic, bluetoothDevice)
            }

            return@async null
        }
    }

    suspend fun sendPacket (outgoingPacket : XyoBluetoothOutgoingPacket, characteristic: XYBluetoothReadCharacteristic, bluetoothDevice: BluetoothDevice) = suspendCoroutine<Any?> { cont ->
        val key = "sendPacket$this"
        characteristic.addResponder(key, object : XYBluetoothReadCharacteristic.XYBluetoothReadCharacteristicResponder {
            override fun onReadRequest(device: BluetoothDevice?): ByteArray? {
                if (bluetoothDevice.address == device?.address) {
                    val sendValue = outgoingPacket.getNext()

                    if (!outgoingPacket.canSendNext) {
                        characteristic.removeResponder(key)
                        cont.resume(null)
                    }

                    return sendValue
                }
                return null
            }
        })
    }

    suspend fun readPacket (writeCharacteristic: XYBluetoothWriteCharacteristic, bluetoothDevice: BluetoothDevice) : ByteArray? = suspendCoroutine<ByteArray?> { cont ->
        var incomingPacket : XyoBluetoothIncomingPacket? = null
        val key = "readPacket$this"

        writeCharacteristic.addResponder(key, object : XYBluetoothWriteCharacteristic.XYBluetoothWriteCharacteristicResponder {
            override fun onWriteRequest(writeRequestValue: ByteArray?, device: BluetoothDevice?): Boolean? {
                if (bluetoothDevice.address == device?.address) {
                    if (writeRequestValue != null) {
                        if (incomingPacket == null) {
                            incomingPacket = XyoBluetoothIncomingPacket(writeRequestValue)
                        } else {
                            val doneValue = incomingPacket?.addPacket(writeRequestValue)
                            if (doneValue != null) {
                                writeCharacteristic.removeResponder(key)
                                cont.resume(doneValue)
                            }

                        }
                        return true
                    }
                }
                return null
            }
        })
    }

    init {
        bluetoothService.addCharacteristic(bluetoothWriteCharacteristic)
        bluetoothService.addCharacteristic(bluetoothReadCharacteristic)
        bluetoothServer.startServer()
        bluetoothServer.addService(bluetoothService)
    }

    companion object {
        private val bluetoothWriteCharacteristic = XYBluetoothWriteCharacteristic(XyoUuids.XYO_WRITE)
        private val bluetoothReadCharacteristic = XYBluetoothReadCharacteristic(XyoUuids.XYO_READ)
        private val bluetoothService = XYBluetoothService(XyoUuids.XYO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    }
}