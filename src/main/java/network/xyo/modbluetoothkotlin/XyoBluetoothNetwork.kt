package network.xyo.modbluetoothkotlin

import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import kotlinx.coroutines.*
import network.xyo.ble.gatt.XYBluetoothError
import network.xyo.ble.gatt.server.XYBluetoothAdvertiser
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.scanner.XYFilteredSmartScanModern
import network.xyo.core.XYBase
import network.xyo.modbluetoothkotlin.client.XyoBluetoothClient
import network.xyo.modbluetoothkotlin.client.XyoBluetoothClientCreator
import network.xyo.modbluetoothkotlin.server.XyoBluetoothServer
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import network.xyo.sdkcorekotlin.network.XyoNetworkProviderInterface
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * An implementation of the XyoNetworkProviderInterface and the XYO Network BLE Protocol
 * that acts as a peripheral and a central to find other devices.
 *
 * @param bleServer The BLE server to use as a peripheral.
 * @param advertiser The BLE advertiser to use when advertising the BLE XYO Service.
 */
class XyoBluetoothNetwork (bleServer: XYBluetoothGattServer, private val advertiser: XYBluetoothAdvertiser, scanner: XYFilteredSmartScanModern, val errorListener : XyoBluetoothNetworkListener?) : XyoNetworkProviderInterface, XYBase() {
    val clientFinder = XyoBluetoothClientCreator(scanner)
    val serverFinder = XyoBluetoothServer(bleServer)
    var connectionRssi : Int? = null

    /**
     * The implementation to stop all network services.
     */
    override fun stop() {
        clientFinder.stop()
        serverFinder.stop()
        stopAdvertiser()
    }

    /**
     * The implementation to find a peer given a procedureCatalogue.
     */
    override fun find(procedureCatalogue: XyoNetworkProcedureCatalogueInterface) = GlobalScope.async {
        connectionRssi = null
        var resumed = false

        return@async suspendCoroutine<XyoNetworkPipe> { cont ->
            val serverKey = "server$this"
            val clientKey = "client$this"

            /**
             * The standard listener to add to connection creators.
             */
            val listener = object : XyoBluetoothPipeCreatorListener {
                override fun onCreatedConnection(connection: XyoBluetoothConnection) {
                    val key = connection.toString()
                    connection.addListener(key, object : XyoBluetoothConnectionListener {
                        override fun onConnectionRequest() {
                            // stopAdvertiser()
                        }

                        override fun onConnectionFail() {
                            serverFinder.start(procedureCatalogue)
                            clientFinder.start(procedureCatalogue)
                            // startAdvertiser()
                        }

                        override fun onCreated(pipe: XyoNetworkPipe) {

                            /**
                             * Shut everything down.
                             */
                            serverFinder.stop()
                            clientFinder.stop()

                            stopAdvertiser()

                            clientFinder.removeListener(clientKey)
                            serverFinder.removeListener(serverKey)


                            if (pipe is XyoBluetoothClient.XyoBluetoothClientPipe) {
                                connectionRssi = pipe.rssi
                            }

                            /**
                             * Resume the find call.
                             */

                            if (!resumed) {
                                resumed = true
                                cont.resume(pipe)
                            }
                        }
                    })
                }
            }

            serverFinder.addListener(serverKey, listener)
            clientFinder.addListener(clientKey, listener)

            serverFinder.start(procedureCatalogue)
            clientFinder.start(procedureCatalogue)

            GlobalScope.launch {
                val error = startAdvertiser().await()
                if (error != null) {
                    errorListener?.onError(error)
                }
            }
        }
    }

    /**
     * Start a advertisement cycle
     */
    private fun startAdvertiser() = GlobalScope.async {
        val conResult = advertiser.changeContactable(true).await()
        if (conResult.error != null) return@async conResult.error

        val modResult = advertiser.changeAdvertisingMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).await()
        if (modResult.error != null) return@async modResult.error

        val levResult = advertiser.changeAdvertisingTxLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).await()
        if (levResult.error != null) return@async levResult.error

        return@async  advertiser.chnagePrimaryService(ParcelUuid(XyoUuids.XYO_SERVICE)).await().error
    }

    /**
     * Stop the current advertisement cycle
     */
    private fun stopAdvertiser() {
        advertiser.stopAdvertising()
    }

    init {
        GlobalScope.launch {
            val error = serverFinder.spinUpServer().await().error

            if (error != null) {
                errorListener?.onError(error)
            }
        }
    }

    interface XyoBluetoothNetworkListener {
        fun onError (error : XYBluetoothError)
    }
}