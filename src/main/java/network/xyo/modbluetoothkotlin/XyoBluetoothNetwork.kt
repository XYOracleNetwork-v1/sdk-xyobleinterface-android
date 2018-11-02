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
import java.util.*
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
    private var canCreate = false
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
        canCreate = false
    }

    /**
     * The implementation to resume all network services.
     */
    fun resume() {
        canCreate = true
    }

    /**
     * The implementation to find a peer given a procedureCatalogue.
     */
    override fun find(procedureCatalogue: XyoNetworkProcedureCatalogueInterface) = GlobalScope.async {
        canCreate = true
        connectionRssi = null
        var resumed = false

        val serverKey = "server$this"
        val clientKey = "client$this"

        val pipe = suspendCoroutine<XyoNetworkPipe> { cont ->
            var isTrying = false
            var found = false

            val job = GlobalScope.launch {
                var onServer = Random().nextBoolean()

                while (!found && !resumed) {
                    if (canCreate) {
                        if (onServer) {
                            logInfo("Starting XYO BLE Server.")
                            serverFinder.start(procedureCatalogue)
                            advertiser.startAdvertising()

                            delay((Math.random()*SWITCH_MAX).toLong())

                            logInfo("Stopping XYO BLE Server.")
                            stopAdvertiser()
                            serverFinder.stop()
                            onServer = false
                        } else {
                            logInfo("Starting XYO BLE Client.")
                            clientFinder.start(procedureCatalogue)

                            delay((Math.random()*SWITCH_MAX).toLong())


                            logInfo("Stopping XYO BLE Client.")
                            clientFinder.stop()
                            onServer = true
                        }
                    } else {
                        println("DO NOT BE HERE")
                        delay(TRY_WAIT_RESOLUTION.toLong())
                    }
                }
            }

            /**
             * The standard listener to add to connection creators.
             */
            val listener = object : XyoBluetoothPipeCreatorListener {
                override fun onCreatedConnection(connection: XyoBluetoothConnection) {
                    val key = connection.toString()
                    connection.addListener(key, object : XyoBluetoothConnectionListener {
                        override fun onConnectionRequest() {
                            isTrying = true
                        }

                        override fun onConnectionFail() {
                            isTrying = false
                        }

                        override fun onCreated(pipe: XyoNetworkPipe) {
                            if (!resumed) {
                                found = true
                                resumed = true
                                job.cancel()
                                cont.resume(pipe)
                            }
                        }
                    })
                }
            }

            serverFinder.addListener(serverKey, listener)
            clientFinder.addListener(clientKey, listener)
        }

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

        return@async pipe
    }

    /**
     * Start a advertisement cycle
     */
    private fun startAdvertiserFirst() = GlobalScope.async {
        val conResult = advertiser.changeContactable(true).await()
        if (conResult.error != null) return@async conResult.error

        val modResult = advertiser.changeAdvertisingMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).await()
        if (modResult.error != null) return@async modResult.error

        val levResult = advertiser.changeAdvertisingTxLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).await()
        if (levResult.error != null) return@async levResult.error

        return@async advertiser.chnagePrimaryService(ParcelUuid(XyoUuids.XYO_SERVICE)).await().error
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

            startAdvertiserFirst().await()
        }
    }

    interface XyoBluetoothNetworkListener {
        fun onError (error : XYBluetoothError)
    }

    companion object {
        /**
         * The max time to allow on either a client or server in milliseconds.
         */
        const val SWITCH_MAX = 30_000

        /**
         * How long wait in between checks of the client or server is still trying.
         */
        const val TRY_WAIT_RESOLUTION = 5_000
    }
}