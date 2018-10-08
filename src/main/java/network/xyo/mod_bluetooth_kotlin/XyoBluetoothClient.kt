package network.xyo.mod_bluetooth_kotlin

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.experimental.*
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYCreator
import network.xyo.ble.gatt.XYBluetoothError
import network.xyo.ble.scanner.XYScanResult
import network.xyo.sdkcorekotlin.data.XyoByteArraySetter
import network.xyo.sdkcorekotlin.data.XyoUnsignedHelper
import network.xyo.sdkcorekotlin.network.XyoNetworkPeer
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import java.nio.ByteBuffer
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine


/**
 * A Bluetooth client that can create a XyoNetworkPipe.
 */
class XyoBluetoothClient(context: Context, device: BluetoothDevice?, hash: Int) : XYBluetoothDevice(context, device, hash) {

    // TODO abstract this value to be the current state of the origin chain (previous hash)
    val hashValue = Random().nextLong()

    fun createPipe(catalogueInterface: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        return@async suspendCoroutine<XyoNetworkPipe?> { cont ->
            GlobalScope.launch {
                val connection = connection {
                    val pipe = doCreatePipe(catalogueInterface).await()

                    cont.resume(pipe)
                    coroutineContext.cancel()
                }.await()



                Log.v("HERE 123", "HELLO")
                cont.resume(null)
                coroutineContext.cancel()
                coroutineContext.cancelChildren()
                cont.context.cancelChildren()
                return@launch
            }
        }
    }

    private fun doCreatePipe(catalogueInterface: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        val sizeEncodedProcedureCatalogue = getSizeEncodedProcedureCatalogue(catalogueInterface)
        // writes the encoded catalogue to the server
        logInfo("Writing catalogue to server.")
        val writeError = findAndWriteCharacteristic(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE, sizeEncodedProcedureCatalogue).await()

        // if there is an error break
        if (writeError.error != null) {
            logInfo("Error writing catalogue to server. ${writeError.error}")
            return@async null
        }

        logInfo("Wrote catalogue to server.")

        logInfo("Going to read the server's response.")

        // read the response from the server
        val incomingPacket = readIncommoding()

        logInfo("Read the server's response.")

        // check if the packet was read successfully
        if (incomingPacket != null) {
            logInfo("Read the server's response (good).")
            return@async createPipeFromResponse(incomingPacket)
        } else {
            logInfo("Error reading the server's response.")
            return@async null
        }
    }

    private fun createPipeFromResponse(incomingPacket: ByteArray): XyoBluetoothClientPipe {
        val sizeOfCatalog = XyoUnsignedHelper.readUnsignedByte(byteArrayOf(incomingPacket[0]))
        val catalog = incomingPacket.copyOfRange(1, sizeOfCatalog + 1)
        val initiationData = incomingPacket.copyOfRange(sizeOfCatalog + 1, incomingPacket.size)
        return XyoBluetoothClientPipe(catalog, initiationData)
    }

    private fun getSizeEncodedProcedureCatalogue(catalogueInterface: XyoNetworkProcedureCatalogueInterface): ByteArray {
        val firstDataToSend = catalogueInterface.getEncodedCanDo()
        val sideOfCatalogue = XyoUnsignedHelper.createUnsignedByte(firstDataToSend.size)
        val merger = XyoByteArraySetter(3)
        merger.add(ByteBuffer.allocate(8).putLong(hashValue).array(), 0)
        merger.add(sideOfCatalogue, 1)
        merger.add(firstDataToSend, 2)
        return merger.merge()
    }

    inner class XyoBluetoothClientPipe(private val role: ByteArray, override val initiationData: ByteArray?) : XyoNetworkPipe() {
        override val peer: XyoNetworkPeer = object : XyoNetworkPeer() {
            override fun getRole(): ByteArray {
                return role
            }

            override fun getTemporaryPeerId(): Int {
                return device?.address?.hashCode() ?: 0
            }
        }

        override fun close(): Deferred<Any?> = GlobalScope.async {
            disconnect().await()
            this@XyoBluetoothClient.close().await()
        }

        override fun send(data: ByteArray, waitForResponse: Boolean): Deferred<ByteArray?> = GlobalScope.async {
            // the packet to send to the server
            val outgoingPacket = XyoBluetoothOutgoingPacket(20, data)
            return@async suspendCoroutine<ByteArray?> { cont ->
                GlobalScope.launch {
                    val status = connection {
                        // the key to add the connection listener
                        val disconnectKey = this.toString()

                        // send a receive a packet
                        val sendAndReceive = GlobalScope.async {

                            // send the data
                            logInfo("Sending entire packet to the server.")

                            val packetError = sendPacket(outgoingPacket).await()

                            logInfo("Sent entire packet to the server.")

                            // check if there was an error
                            if (packetError == null) {
                                logInfo("Sent entire packet to the server (good).")
                                var valueIn: ByteArray? = null

                                // read the incoming packet

                                if (waitForResponse) {
                                    logInfo("Going to read entire server response packet.")

                                    valueIn = readIncommoding()
                                }

                                logInfo("Have read entire server response packet.")

                                // remove the disconnect listener
                                removeListener(disconnectKey)

                                // resume the job
                                cont.resume(valueIn)
                            } else {
                                logInfo("Error sending entire packet to the server.")

                                // remove the disconnect listener
                                removeListener(disconnectKey)

                                // resume the job
                                cont.resume(null)
                            }
                        }

                        // add the disconnect listener
                        logInfo("Adding disconnect listener.")
                        addListener(disconnectKey, object : XYBluetoothDevice.Listener() {
                            override fun connectionStateChanged(device: XYBluetoothDevice, newState: Int) {
                                when (newState) {

                                    // this is called when a device disconnects
                                    BluetoothGatt.STATE_DISCONNECTED -> {
                                        logInfo("Someone disconnected.")

                                        // check if the context is still active
                                        if (cont.context.isActive) {
                                            logInfo("Context is still active.")

                                            // remove the disconnect listener
                                            removeListener(disconnectKey)

                                            // stop sending and receiving packets
                                            logInfo("Canceling send and receive.")

                                            // resume the routine
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

    private fun sendPacket(outgoingPacket: XyoBluetoothOutgoingPacket): Deferred<XYBluetoothError?> = GlobalScope.async {
        logInfo("Sending Entire Packet sendPacket.")
        // while the packet can still send
        while (outgoingPacket.canSendNext) {
            // write to the server
            logInfo("Writing to the server.")
            val error = findAndWriteCharacteristic(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE, outgoingPacket.getNext()).await()

            // if there was an error sending break and return the error
            if (error.error != null) {
                logInfo("Error writing to the server.")
                return@async error.error
            }
            logInfo("Wrote to the server,.")
        }

        // if you are here then there was no error
        logInfo("Sending entire packet to server (good).")
        return@async null
    }

    private suspend fun readIncommoding() = suspendCoroutine<ByteArray?> { cont ->
        logInfo("Reading incoming packet readIncommoding.")
        GlobalScope.launch {
            // read the first packet
            logInfo("Reading first packet from server.")
            val firstReadPacket = findAndReadCharacteristicBytes(XyoUuids.XYO_SERVICE, XyoUuids.XYO_READ).await()

            // check if there is an error
            if (firstReadPacket.error == null) {

                // make sure there is a value
                val firstReadPacketValue = firstReadPacket.value
                if (firstReadPacketValue != null) {

                    logInfo("Read first packet from server.")

                    // create the incoming packet
                    val incomingPacket = XyoBluetoothIncomingPacket(firstReadPacketValue)

                    // with the created packet, read through all of it
                    val readValue = readEntirePacket(incomingPacket)

                    cont.resume(readValue)
                    return@launch
                }
            }

            // if you are here, there was an error
            logInfo("Error reading ${firstReadPacket.error}.")
            cont.resume(null)
            coroutineContext.cancel()
        }

    }

    private suspend fun readEntirePacket(incomingPacket: XyoBluetoothIncomingPacket): ByteArray? {
        logInfo("Going to read entire packet readEntirePacket.")

        // while the incoming packet can still send
        while (!incomingPacket.done) {
            logInfo("Going to read sub packet.")
            // read a packet
            val packet = findAndReadCharacteristicBytes(XyoUuids.XYO_SERVICE, XyoUuids.XYO_READ).await()

            // check the error
            if (packet.error == null) {

                // make sure there is a value
                val packetValue = packet.value
                if (packetValue != null) {

                    logInfo("Read sub packet.")

                    // add the packet
                    val donePacket = incomingPacket.addPacket(packetValue)

                    // check if completed and return
                    if (donePacket != null) {

                        logInfo("Done reading sub packet.")
                        // if you are here the packet is done
                        return donePacket
                    }
                }
            } else {
                break
            }
        }

        // if you are here there was an error
        return null
    }

    companion object : XYCreator() {
        fun enable(enable: Boolean) {
            if (enable) {
                serviceToCreator[XyoUuids.XYO_SERVICE] = this
            } else {
                serviceToCreator.remove(XyoUuids.XYO_SERVICE)
            }
        }

        override fun getDevicesFromScanResult(context: Context, scanResult: XYScanResult, globalDevices: HashMap<Int, XYBluetoothDevice>, foundDevices: HashMap<Int, XYBluetoothDevice>) {
            val device = scanResult.device

            // get the device address to make sure you don't connect to yourself
            val bluetoothManager = context.applicationContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter

            val isAXyoDevice = scanResult.scanRecord?.serviceUuids?.contains(ParcelUuid(XyoUuids.XYO_SERVICE))

            if (device != null && isAXyoDevice == true && bluetoothAdapter?.address != device.address) {
                val hash = device.address.hashCode()
                val createdDevice = XyoBluetoothClient(context, device, hash)

                foundDevices[hash] = createdDevice
                globalDevices[hash] = createdDevice
            }
        }
    }
}