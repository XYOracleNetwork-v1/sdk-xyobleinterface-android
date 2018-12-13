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
        Log.v("WIN", "CLIENT CREATE PIPE")
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
        Log.v("WIN", "WRITING CATAOGUGE TO SERVER CLIENT")
        logInfo("Writing catalogue to server.")
        val readCharacteristic = findCharacteristic(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE).await().value

        if (readCharacteristic != null) {
            Log.v("WIN", "SET CHARISTIC NOTIFY")
            setCharacteristicNotify(readCharacteristic, true)
        }

        val writeError = findAndWriteCharacteristic(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE, sizeEncodedProcedureCatalogue).await()

        // if there is an error break
        if (writeError.error != null) {
            Log.v("WIN", "ERROR WRITING CATLOGUE TO SERVER")
            logInfo("Error writing catalogue to server. ${writeError.error}")
            disconnect().await()
            close().await()
            return@async null
        }

        Log.v("WIN", "WROTE CATAOFUE TO SERVER")
        logInfo("Wrote catalogue to server.")


        Log.v("WIN", "GOING TO READ SERVERS RESPONSE")
        logInfo("Going to read the server's response.")

        // read the response from the server

        val incomingPacket = readIncommoding()

        Log.v("WIN", "READ SERVERS RESPONSE")
        logInfo("Read the server's response. ${incomingPacket?.size}")

        // check if the packet was read successfully
        if (incomingPacket != null) {
            Log.v("WIN", "READ SERVERS RESPONSE GOOD CLIENT")
            logInfo("Read the server's response (good).")
            return@async createPipeFromResponse(incomingPacket)
        } else {
            Log.v("WIN", "READ SERVERS RESPONSE BAD CLIENT")
            disconnect().await()
            close().await()
            logInfo("Error reading the server's response.")
            return@async null
        }
    }

    private fun createPipeFromResponse(incomingPacket: ByteArray): XyoBluetoothClientPipe? {
        val sizeOfCatalog = incomingPacket[0].toInt() and 0xFFFF
        if (sizeOfCatalog + 1 > incomingPacket.size) {
            Log.v("WIN", "CAT ERROR CLEINT")
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
            Log.v("WIN", "CLOSE CLIENT")
            disconnect().await()
            this@XyoBluetoothClient.close().await()
        }

        override fun send(data: ByteArray, waitForResponse: Boolean): Deferred<ByteArray?> = GlobalScope.async {
            Log.v("WIN", "SEND CLIENT")
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

                                    Log.v("WIN", "CHAR WAS CHJANGED CLIENT")

                                    if (characteristic?.uuid == XyoUuids.XYO_WRITE) {
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

                            Log.v("WIN", "SENDING ENTIRE PACKET TO SERVER")
                            val packetError = sendPacket(data).await()
                            Log.v("WIN", "SENT ENTIRE PACKET TO SERVER")

                            logInfo("Sent entire packet to the server.")

                            // check if there was an error
                            if (packetError == null) {
                                Log.v("WIN", "SENT PACKET TO SERVER GOOD")
                                logInfo("Sent entire packet to the server (good).")
                                var valueIn: ByteArray? = null

                                // read the incoming packet

                                if (waitForResponse) {
                                    Log.v("WIN", "GOING TO READ SERVER RESPONSE PACKEYT")
                                    logInfo("Going to read entire server response packet.")

                                    // if not has been notified, wait for a notification
                                    if (!hasNotified) {
                                        Log.v("WIN", "HAS NO BEEN NOTIFIES SO WILL WAIT")
                                        removeGattListener(notifyListenerName)
                                        waitForNotification(XyoUuids.XYO_WRITE).await()
                                        Log.v("WIN", "RECIVIDED NOTIFY")
                                    }

                                    Log.v("WIN", "GOING TO READ NOW CLIENT")
                                    valueIn = readIncommoding()
                                    Log.v("WIN", "DONE READING NOW")
                                }

                                logInfo("Have read entire server response packet.")

                                // remove the disconnect listener
                                removeListener(disconnectKey)

                                // resume the job
                                cont.resume(valueIn)
                            } else {
                                Log.v("WIN", "ERROR SEWNDING PACKET TO THE SERVER FROM CLIENT")
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
                                        Log.v("WIN", "DISCONNECTED CLIENT")

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
        val value = findAndReadCharacteristicBytes(XyoUuids.XYO_SERVICE, XyoUuids.XYO_WRITE).await()
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