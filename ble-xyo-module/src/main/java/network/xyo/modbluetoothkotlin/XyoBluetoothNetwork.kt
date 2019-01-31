package network.xyo.modbluetoothkotlin

import android.util.Log
import kotlinx.coroutines.*
import network.xyo.ble.gatt.XYBluetoothError
import network.xyo.ble.gatt.server.XYBluetoothAdvertiser
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.scanner.XYSmartScanModern
import network.xyo.core.XYBase
import network.xyo.modbluetoothkotlin.advertiser.XyoBluetoothAdvertiser
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
 * @param userAdvertiser The BLE advertiser to use when advertising the BLE XYO Service.
 */
class XyoBluetoothNetwork (bleServer: XYBluetoothGattServer,
                           userAdvertiser: XYBluetoothAdvertiser,
                           scanner: XYSmartScanModern,
                           private val errorListener : XyoBluetoothNetworkListener?) : XyoNetworkProviderInterface, XYBase() {

    private var canCreate = false

    val clientFinder = XyoBluetoothClientCreator(scanner)
    val serverFinder = XyoBluetoothServer(bleServer)
    var connectionRssi : Int? = null
    val advertiser = XyoBluetoothAdvertiser(
            Random().nextInt(Short.MAX_VALUE + 1).toShort(),
            Random().nextInt(Short.MAX_VALUE + 1).toShort(),
            userAdvertiser
    )

    /**
     * The implementation to stop all network services.
     */
    override fun stop() {
        clientFinder.stop()
        serverFinder.stop()
        advertiser.stopAdvertiser()
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
            GlobalScope.launch {
                val connectionCreationListener = object : XyoBluetoothPipeCreatorListener {
                    override fun onCreatedConnection(connection: XyoBluetoothConnection) {
                        val key = connection.toString()
                        connection.addListener(key, object : XyoBluetoothConnectionListener {
                            override fun onConnectionRequest() {}
                            override fun onConnectionFail() {}
                            override fun onCreated(pipe: XyoNetworkPipe) {
                                if (!resumed) {
                                    resumed = true
                                    cont.resume(pipe)
                                }
                            }
                        })
                    }
                }

                serverFinder.addListener(serverKey, connectionCreationListener)
                clientFinder.addListener(clientKey, connectionCreationListener)
                advertiser.startAdvertiser().await()
                serverFinder.start(procedureCatalogue)
                clientFinder.start(procedureCatalogue)
            }
        }

        serverFinder.stop()
        clientFinder.stop()
        advertiser.stopAdvertiser()
        clientFinder.removeListener(clientKey)
        serverFinder.removeListener(serverKey)

        if (pipe is XyoBluetoothClient.XyoBluetoothClientPipe) {
            connectionRssi = pipe.rssi
        }

        return@async pipe
    }



    init {
        GlobalScope.launch {
            val error = serverFinder.spinUpServer().await().error

            if (error != null) {
                errorListener?.onError(error)
            }

            advertiser.configureAdvertiser()
        }
    }

    interface XyoBluetoothNetworkListener {
        fun onError (error : XYBluetoothError)
    }
}