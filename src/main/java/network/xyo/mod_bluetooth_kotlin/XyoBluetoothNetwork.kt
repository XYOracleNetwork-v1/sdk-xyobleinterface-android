package network.xyo.mod_bluetooth_kotlin

import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import android.util.LongSparseArray
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import network.xyo.ble.gatt.server.*
import network.xyo.ble.scanner.XYFilteredSmartScanModern
import network.xyo.core.XYBase
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import network.xyo.sdkcorekotlin.network.XyoNetworkProviderInterface
import java.net.Socket
import java.util.*
import kotlin.collections.HashMap
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * An implementation of the XyoNetworkProviderInterface and the XYO Network BLE Protocol
 * that acts as a peripheral and a central to find other devices.
 *
 * @param bleServer The BLE server to use as a peripheral.
 * @param advertiser The BLE advertiser to use when advertising the BLE XYO Service.
 * @param context The Android Context to use when using BLE services.
 */
class XyoBluetoothNetwork (bleServer : XYBluetoothGattServer, private val advertiser: XYBluetoothAdvertiser, context : Context) : XyoNetworkProviderInterface, XYBase() {
    private val clientFinder = XyoBluetoothClientCreator(XYFilteredSmartScanModern(context))
    private val serverFinder = XyoBluetoothServer(bleServer)

    /**
     * The implementation to find a peer given a procedureCatalogue.
     */
    override suspend fun find(procedureCatalogue: XyoNetworkProcedureCatalogueInterface) = suspendCoroutine <XyoNetworkPipe>{ cont ->
        val serverKey = "server$this"
        val clientKey = "client$this"

        /**
         * How many connections are waiting to be resolved.
         */
        var waitingOnCount = 0

        /**
         * How many connections are finished.
         */
        val doneConnections = ArrayList<XyoBluetoothConnection>()

        /**
         * The standard listener to add to connection creators.
         */
        val listener = object : XyoBluetoothPipeCreatorListener {
            override fun onCreatedConnection(connection: XyoBluetoothConnection) {
                val key = "connection${connection.hashValue}"
                connection.addListener(key, object : XyoBluetoothConnectionListener {

                    override fun onConnectionRequest() {
                        waitingOnCount++

                        if (STRICT_TURN_OFF) {
                            serverFinder.stop()
                            clientFinder.stop()
                        }
                    }

                    override fun onConnectionFail() {
                        waitingOnCount--
                        connection.pipe?.close()

                        if (STRICT_TURN_OFF) {
                            serverFinder.start(procedureCatalogue)
                            clientFinder.start(procedureCatalogue)
                        }
                    }

                    override fun onCreated(pipe: XyoNetworkPipe) {
                        waitingOnCount--
                        doneConnections.add(connection)

                        /**
                         * When the wait count equals the number of connections we will resolve.
                         */
                        var highest : XyoBluetoothConnection? = null

                        /**
                         * Find the connection with the highest hash value.
                         */
                        for (doneConnection in doneConnections) {
                            if (compareUnsignedLongs(doneConnection.hashValue, highest?.hashValue ?: 0)) {
                                doneConnection.removeListener("connection${doneConnection.hashValue}")
                                highest = doneConnection
                            } else {
                                /**
                                 * If it not higher, disconnect.
                                 */
                                doneConnection.removeListener("connection${doneConnection.hashValue}")
                                doneConnection.pipe?.close()
                            }
                        }


                        /**
                         * Shut everything down.
                         */
                        serverFinder.stop()
                        clientFinder.stop()

                        stopAdvertiser()

                        clientFinder.removeListener(clientKey)
                        serverFinder.removeListener(serverKey)

                        /**
                         * Resume the find call.
                         */
                        cont.resume(highest?.pipe!!)
                    }
                })
            }
        }

        serverFinder.addListener(serverKey, listener)
        clientFinder.addListener(clientKey, listener)

        serverFinder.start(procedureCatalogue)
        clientFinder.start(procedureCatalogue)
        startAdvertiser()
    }

    /**
     * Start a advertisement cycle
     */
    private fun startAdvertiser () = async {
        advertiser.changeContactable(true).await()
        advertiser.changeIncludeDeviceName(true).await()
        advertiser.changeAdvertisingMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).await()
        advertiser.changeAdvertisingTxLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).await()
        advertiser.chnagePrimaryService(ParcelUuid(XyoUuids.XYO_SERVICE)).await()
    }

    /**
     * Stop the current advertisement cycle
     */
    private fun stopAdvertiser () {
        advertiser.stopAdvertising()
    }

    private fun compareUnsignedLongs (compare : Long, to : Long) : Boolean {
        // todo implement unsigned comparison
        return Math.abs(compare) > to
    }

    companion object {
        /**
         * Disable the other network when another network is trying to connect.
         */
        const val STRICT_TURN_OFF = false
    }
}