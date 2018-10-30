package network.xyo.mod_bluetooth_kotlin

import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.cancel
import kotlinx.coroutines.experimental.cancelChildren
import network.xyo.ble.gatt.server.XYBluetoothAdvertiser
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.scanner.XYFilteredSmartScanModern
import network.xyo.core.XYBase
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import network.xyo.sdkcorekotlin.network.XyoNetworkProviderInterface
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * An implementation of the XyoNetworkProviderInterface and the XYO Network BLE Protocol
 * that acts as a peripheral and a central to find other devices.
 *
 * @param bleServer The BLE server to use as a peripheral.
 * @param advertiser The BLE advertiser to use when advertising the BLE XYO Service.
 * @param context The Android Context to use when using BLE services.
 */
class XyoBluetoothNetwork(bleServer: XYBluetoothGattServer, private val advertiser: XYBluetoothAdvertiser, scanner: XYFilteredSmartScanModern) : XyoNetworkProviderInterface, XYBase() {
    private val clientFinder = XyoBluetoothClientCreator(scanner)
    private val serverFinder = XyoBluetoothServer(bleServer)
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
                            if (STRICT_TRY) {
                                serverFinder.stop()
                                clientFinder.stop()
                            }
                        }

                        override fun onConnectionFail() {
                            if (STRICT_TRY) {
                                serverFinder.start(procedureCatalogue)
                                clientFinder.start(procedureCatalogue)
                            }
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
                            println("RESUMED")
                            cont.resume(pipe)
                            coroutineContext.cancelChildren()
                            coroutineContext.cancel()
                            cont.context.cancel()
                            cont.context.cancelChildren()
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
    }

    /**
     * Start a advertisement cycle
     */
    private fun startAdvertiser() = GlobalScope.async {
        advertiser.changeContactable(true).await()
        advertiser.changeIncludeDeviceName(true).await()
        advertiser.changeAdvertisingMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).await()
        advertiser.changeAdvertisingTxLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).await()
        advertiser.chnagePrimaryService(ParcelUuid(XyoUuids.XYO_SERVICE)).await()
    }

    /**
     * Stop the current advertisement cycle
     */
    private fun stopAdvertiser() {
        advertiser.stopAdvertising()
    }

    companion object {
        /**
         * Disable the other network when another network is trying to connect.
         */
        const val STRICT_TRY = false
    }
}