package network.xyo.modbluetoothkotlin.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYCreator
import network.xyo.ble.gatt.XYBluetoothError
import network.xyo.ble.scanner.XYScanResult
import network.xyo.modbluetoothkotlin.XyoUuids
import network.xyo.sdkcorekotlin.network.XyoNetworkPeer
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * A Bluetooth client that can create a XyoNetworkPipe.
 */
class XyoBluetoothClient(context: Context, device: BluetoothDevice?, hash : Int) : XYBluetoothDevice(context, device, hash) {
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
        val readCharacteristic = findCharacteristic(XyoUuids.XYO_SERVICE, XyoUuids.XYO_READ).await().value

        if (readCharacteristic != null) {
            setCharacteristicNotify(readCharacteristic, true)
        }

        val writeError = findAndWriteCharacteristic(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE, sizeEncodedProcedureCatalogue).await()

        // if there is an error break
        if (writeError.error != null) {
            logInfo("Error writing catalogue to server. ${writeError.error}")
            disconnect().await()
            close().await()
            return@async null
        }

        logInfo("Wrote catalogue to server.")

        logInfo("Going to read the server's response.")

        // read the response from the server
        val incomingPacket = readIncommoding()

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

    private fun createPipeFromResponse(incomingPacket: ByteArray): XyoBluetoothClientPipe {
        val sizeOfCatalog = incomingPacket[0].toInt() and 0xFFFF
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
                        val notifyListenerName = "xyoNotifyListener$nowNano"
                        var hasNotified = false

                        GlobalScope.launch {
                            // listen for a notification before sending, it is possible to receive a notification before reading is done
                            addGattListener(notifyListenerName, object : XYBluetoothGattCallback() {
                                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                                    super.onCharacteristicChanged(gatt, characteristic)

                                    if (characteristic?.uuid == XyoUuids.XYO_READ) {
                                        removeGattListener(notifyListenerName)
                                        hasNotified = true
                                    }
                                }
                            })
                        }

                        // send a receive a packet
                        val sendAndReceive = GlobalScope.async {

                            // send the data
                            logInfo("Sending entire packet to the server.")

                            val packetError = sendPacket(data).await()

                            logInfo("Sent entire packet to the server.")

                            // check if there was an error
                            if (packetError == null) {
                                logInfo("Sent entire packet to the server (good).")
                                var valueIn: ByteArray? = null

                                // read the incoming packet

                                if (waitForResponse) {
                                    logInfo("Going to read entire server response packet.")

                                    // if not has been notified, wait for a notification
                                    if (!hasNotified) {
                                        removeGattListener(notifyListenerName)
                                        waitForNotification(XyoUuids.XYO_READ).await()
                                    }

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

    private fun sendPacket(outgoingPacket: ByteArray): Deferred<XYBluetoothError?> = GlobalScope.async {
        logInfo("Sending Entire Packet sendPacket.")
        // while the packet can still send
        val buffer = ByteBuffer.allocate(outgoingPacket.size + 4)
        buffer.putInt(outgoingPacket.size + 4)
        buffer.put(outgoingPacket)
        findAndWriteCharacteristic(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE, buffer.array()).await()
        return@async null
    }

    private suspend fun readIncommoding() : ByteArray? {
        val value = findAndReadCharacteristicBytes(XyoUuids.XYO_SERVICE, XyoUuids.XYO_READ).await()
        return value.value
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