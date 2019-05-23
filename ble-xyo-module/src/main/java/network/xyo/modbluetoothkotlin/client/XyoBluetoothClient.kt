package network.xyo.modbluetoothkotlin.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYCreator
import network.xyo.ble.devices.XYIBeaconBluetoothDevice
import network.xyo.ble.gatt.peripheral.XYBluetoothError
import network.xyo.ble.gatt.peripheral.XYBluetoothGattCallback
import network.xyo.ble.scanner.XYScanResult
import network.xyo.modbluetoothkotlin.XyoUuids
import network.xyo.modbluetoothkotlin.packet.XyoBluetoothIncomingPacket
import network.xyo.modbluetoothkotlin.packet.XyoBluetoothOutgoingPacket
import network.xyo.sdkcorekotlin.network.XyoAdvertisePacket
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.schemas.XyoSchemas
import network.xyo.sdkobjectmodelkotlin.buffer.XyoBuff
import network.xyo.sdkobjectmodelkotlin.objects.toHexString
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.experimental.and


/**
 * A Bluetooth client that can create a XyoNetworkPipe. This pipe can be used with the sdk-core-kotlin to talk to
 * other XYO enabled devices.
 *
 * @property context Context of the device
 * @property device The android bluetooth device
 * @property hash The unique hash of the device
 */
open class XyoBluetoothClient : XYIBeaconBluetoothDevice {

    constructor(context: Context, scanResult: XYScanResult, hash: Int) : super(context, scanResult, hash.toString())

    constructor(context: Context, scanResult: XYScanResult, hash: Int, transport: Int) : super(context, scanResult, hash.toString(), transport)

    /**
     * The standard size of the MTU of the connection. This value is used when chunking large amounts of data.
     */
    private var mtu = DEFAULT_MTU

    /**
     * creates a XyoNetworkPipe with THIS bluetooth device.
     * @return A Deferred XyoNetworkPipe if successful, null if not.
     */
    fun createPipe(): Deferred<XyoNetworkPipe?> = GlobalScope.async {
        findAndWriteCharacteristicNotify(XyoUuids.XYO_SERVICE, XyoUuids.XYO_PIPE, true).await()

        val requestMtu = requestMtu(MAX_MTU).await()

        mtu = (requestMtu.value ?: mtu) - 3

        return@async XyoBluetoothClientPipe(rssi)
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
    inner class XyoBluetoothClientPipe(val rssi: Int?) : XyoNetworkPipe {

        override val initiationData: XyoAdvertisePacket? = null

        /**
         * Closes the pipe between parties. In this case, disconnects from the device and closes the GATT. This should
         * be called after the pipe is finished being used.
         */
        override fun close(): Deferred<Any?> = GlobalScope.async {
            disconnect()
            this@XyoBluetoothClient.close()
        }

        override fun getNetworkHeretics(): Array<XyoBuff> {
            val toReturn = ArrayList<XyoBuff>()

            if (rssi != null) {
                val encodedRssi = XyoBuff.newInstance(XyoSchemas.RSSI, byteArrayOf(rssi.toByte()))
                toReturn.add(encodedRssi)
            }

            val pwr = XyoBuff.newInstance(XyoSchemas.BLE_POWER_LVL, byteArrayOf(power))
            toReturn.add(pwr)

            return toReturn.toTypedArray()
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
                val disconnectKey = this.toString() + Math.random().toString()

                val sendAndReceive = GlobalScope.async {

                    val readJob = readIncoming()
                    val packetError = chunkSend(data, XyoUuids.XYO_PIPE, XyoUuids.XYO_SERVICE, 4).await()

                    log.info("Sent entire packet to the server.")
                    if (packetError == null) {
                        log.info("Sent entire packet to the server (good).")
                        var valueIn: ByteArray? = null


                        if (waitForResponse) {
                            valueIn = readJob.await()
                        }

                        log.info("Have read entire server response packet. ${valueIn?.toHexString()}")
                        removeListener(disconnectKey)
                        cont.resume(valueIn)
                    } else {
                        log.info("Error sending entire packet to the server. $packetError")
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
            }
        }
    }

    /**
     * Preforms a chunk send
     *
     * @param outgoingPacket The packet to send to the server. This value will be chunked accordingly, if larger than
     * the MTU of the connection.
     * @param characteristic The characteristic UUID to write to.
     * @param service The service UUID to write to.
     * @param sizeOfSize size of the packet header size to send
     * @return An XYBluetoothError if there was an issue writing the packet.
     */
    protected fun chunkSend(outgoingPacket: ByteArray, characteristic: UUID, service: UUID, sizeOfSize: Int): Deferred<XYBluetoothError?> = GlobalScope.async {
        return@async suspendCoroutine<XYBluetoothError?> { cont ->
            GlobalScope.launch {
                val chunkedOutgoingPacket = XyoBluetoothOutgoingPacket(mtu, outgoingPacket, sizeOfSize)

                while (chunkedOutgoingPacket.canSendNext) {
                    val error = findAndWriteCharacteristic(
                            service,
                            characteristic,
                            chunkedOutgoingPacket.getNext(),
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ).await().error
                    delay(500)
                    if (error != null) {
                        cont.resume(error)
                        return@launch
                    }
                }

                cont.resume(null)
            }
        }
    }


    /**
     * Reads an incoming packet by listening for notifications. This function must be invoked before any notifications
     * are sent or else will return null. Timeout of the first notification is defined with FIRST_NOTIFY_TIMEOUT, in
     * milliseconds and notification delta timeout is defined as NOTIFY_TIMEOUT in milliseconds.
     *
     * @return A deferred ByteArray of the value read. If there was an error or timeout, will return null.
     */
    private fun readIncoming(): Deferred<ByteArray?> = GlobalScope.async {
        return@async suspendCoroutine<ByteArray?> { cont ->
            val key = this.toString() + Math.random().toString()

            centralCallback.addListener(key, object : XYBluetoothGattCallback() {
                var numberOfPackets = 0
                var hasResumed = false

                var timeoutJob: Job = GlobalScope.launch {
                    delay(FIRST_NOTIFY_TIMEOUT.toLong())
                    hasResumed = true
                    centralCallback.removeListener(key)
                    cont.resume(null)
                }

                var incomingPacket: XyoBluetoothIncomingPacket? = null

                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                    super.onCharacteristicChanged(gatt, characteristic)
                    val value = characteristic?.value

                    if (characteristic?.uuid == XyoUuids.XYO_PIPE && !hasResumed) {

                        if (numberOfPackets == 0 && value != null) {
                            incomingPacket = XyoBluetoothIncomingPacket(value)
                        } else if (value != null) {
                            incomingPacket?.addPacket(value)
                        }

                        if (incomingPacket?.done == true) {
                            hasResumed = true
                            centralCallback.removeListener(key)
                            timeoutJob.cancel()
                            cont.resume(incomingPacket?.getCurrentBuffer())
                        } else {
                            timeoutJob.cancel()
                            timeoutJob = GlobalScope.launch {
                                delay(NOTIFY_TIMEOUT.toLong())
                                hasResumed = true
                                centralCallback.removeListener(key)
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
        const val FIRST_NOTIFY_TIMEOUT = 12_000
        const val NOTIFY_TIMEOUT = 10_000
        const val MAX_MTU = 512
        const val DEFAULT_MTU = 22

        @SuppressLint("UseSparseArrays") //SparseArrays cannot use Byte as key
        val xyoManufactureIdToCreator = HashMap<Byte, XYCreator>()

        /**
         * Enable this device to be created on scan.
         *
         * @param enable Weather or not to enable the device.
         */
        fun enable(enable: Boolean) {
            if (enable) {
                serviceToCreator[XyoUuids.XYO_SERVICE] = this
                uuidToCreator[XyoUuids.XYO_SERVICE] = this
                XYBluetoothGattCallback.blockNotificationCallback = true
                XYIBeaconBluetoothDevice.enable(true)
            } else {
                serviceToCreator.remove(XyoUuids.XYO_SERVICE)
                uuidToCreator.remove(XyoUuids.XYO_SERVICE)
                XYIBeaconBluetoothDevice.enable(false)
                XYBluetoothGattCallback.blockNotificationCallback = false
            }
        }

        override fun getDevicesFromScanResult(
                context: Context,
                scanResult: XYScanResult,
                globalDevices: ConcurrentHashMap<String, XYBluetoothDevice>,
                foundDevices: HashMap<String,
                        XYBluetoothDevice>
        ) {
            val hash = scanResult.device?.address.hashCode()

            if ((!foundDevices.containsKey(hash.toString())) && (!globalDevices.containsKey(hash.toString()))) {
                val ad = scanResult.scanRecord?.getManufacturerSpecificData(0x4c)

                if (ad?.size == 23) {
                    val id = ad[19]

                    // masks the byte with 00111111
                    if (xyoManufactureIdToCreator.containsKey(id and 0x3f)) {
                        xyoManufactureIdToCreator[id and 0x3f]?.getDevicesFromScanResult(context, scanResult, globalDevices, foundDevices)
                        return
                    }
                }

                val createdDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    XyoBluetoothClient(context, scanResult, hash, BluetoothDevice.TRANSPORT_LE)
                } else {
                    XyoBluetoothClient(context, scanResult, hash)
                }

                foundDevices[hash.toString()] = createdDevice
                globalDevices[hash.toString()] = createdDevice
            }
        }
    }
}
