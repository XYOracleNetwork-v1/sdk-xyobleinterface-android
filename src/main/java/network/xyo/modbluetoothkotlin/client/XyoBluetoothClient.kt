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
import network.xyo.sdkcorekotlin.schemas.XyoSchemas
import network.xyo.sdkobjectmodelkotlin.objects.toHexString
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * A Bluetooth client that can create a XyoNetworkPipe.
 */
class XyoBluetoothClient(context: Context, device: BluetoothDevice?, hash : Int) : XYBluetoothDevice(context, device, hash) {
    private var mtu = 20

    fun createPipe(catalogueInterface: XyoNetworkProcedureCatalogueInterface): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        return@async suspendCoroutine<XyoNetworkPipe?> { cont ->
            GlobalScope.launch {
                val connection = connection {
                    val pipe = doCreatePipe(catalogueInterface).await()

                    cont.resume(pipe)
                    coroutineContext.cancel()
                }.await()



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

        findAndWriteCharacteristicNotify(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE, true).await()

        if (requestMtu(100).await().error == null) {
            mtu = 90
        }


        val readJob = readIncommoding()
        val writeError = sendPacket(sizeEncodedProcedureCatalogue).await()

        // if there is an error break
        if (writeError != null) {
            logInfo("Error writing catalogue to server. $writeError")
            disconnect().await()
            close().await()
            return@async null
        }

        logInfo("Wrote catalogue to server.")


        logInfo("Going to read the server's response.")

        val incomingPacket = readJob.await()

        logInfo("Read the server's response. ${incomingPacket?.size}")

        // check if the packet was read successfully
        if (incomingPacket != null) {
            logInfo("Read the server's response (good).")
            return@async createPipeFromResponse(incomingPacket)
        } else {
            disconnect().await()
            close().await()
            logInfo("Error reading the server's response.")
            return@async null
        }
    }

    private fun createPipeFromResponse(incomingPacket: ByteArray): XyoBluetoothClientPipe? {
        val sizeOfCatalog = incomingPacket[0].toInt() and 0xFFFF
        if (sizeOfCatalog + 1 > incomingPacket.size) {
            return null
        }
        val catalog = incomingPacket.copyOfRange(1, sizeOfCatalog + 1)
        val initiationData = incomingPacket.copyOfRange(sizeOfCatalog + 1, incomingPacket.size)
        return XyoBluetoothClientPipe(catalog, initiationData, rssi)
    }

    private fun getSizeEncodedProcedureCatalogue(catalogueInterface: XyoNetworkProcedureCatalogueInterface): ByteArray {
        val firstDataToSend = catalogueInterface.getEncodedCanDo()
        val buff = ByteBuffer.allocate(1 + firstDataToSend.size)
        buff.put(firstDataToSend.size.toByte())
        buff.put(firstDataToSend)
        return buff.array()
    }

    inner class XyoBluetoothClientPipe(private val role: ByteArray, override val initiationData: ByteArray?, val rssi : Int?) : XyoNetworkPipe() {
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
            return@async suspendCoroutine<ByteArray?> { cont ->
                GlobalScope.launch {
                    val status = connection {
                        // the key to add the connection listener
                        val disconnectKey = this.toString()

                        // send a receive a packet
                        val sendAndReceive = GlobalScope.async {

                            // send the data
                            logInfo("Sending entire packet to the server.")

                            val readJob = readIncommoding()
                            val packetError = sendPacket(data).await()

                            logInfo("Sent entire packet to the server.")

                            // check if there was an error
                            if (packetError == null) {
                                logInfo("Sent entire packet to the server (good).")
                                var valueIn: ByteArray? = null

                                // read the incoming packet

                                if (waitForResponse) {
                                    logInfo("Going to read entire server response packet.")

                                    valueIn = readJob.await()
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

    private fun readIncommoding() : Deferred<ByteArray?> = GlobalScope.async {
        return@async readEntire()
    }

    private suspend fun readEntire() = suspendCoroutine<ByteArray?> { cont ->
        val key = this.toString() + Math.random().toString()

        addGattListener(key, object : XYBluetoothGattCallback() {
            var numberOfPackets = 0
            var hasResumed = false

            var timeoutJob : Job = GlobalScope.launch {
                delay(5_000)
                hasResumed = true
                removeGattListener(key)
                cont.resume(null)
            }


            var incomingPacket : XyoBluetoothIncomingPacket? = null

            @Synchronized
            override fun onCharacteristicChangedValue(gatt: BluetoothGatt?, characteristicToRead: BluetoothGattCharacteristic?, value: ByteArray?) {
                super.onCharacteristicChangedValue(gatt, characteristicToRead, value)

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
                            delay(3_000)
                            hasResumed = true
                            removeGattListener(key)
                            cont.resume(null)
                        }
                    }


                    timeoutJob.cancel()

                    numberOfPackets++
                }
            }
        })
    }



    companion object : XYCreator() {
        fun enable(enable: Boolean) {
            if (enable) {
                serviceToCreator[XyoUuids.XYO_SERVICE] = this
            } else {
                serviceToCreator.remove(XyoUuids.XYO_SERVICE)
            }
        }

        override fun getDevicesFromScanResult(context: Context, scanResult: XYScanResult, globalDevices: ConcurrentHashMap<Int, XYBluetoothDevice>, foundDevices: HashMap<Int, XYBluetoothDevice>) {
            val device = scanResult.device

            // get the device address to make sure you don't connect to yourself
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