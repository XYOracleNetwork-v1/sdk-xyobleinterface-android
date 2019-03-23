package network.xyo.modbluetoothkotlin

import network.xyo.core.XYBase
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import java.util.*

/**
 * The base class for "Pipe Creators", in this case, bluetooth client and server.
 */
abstract class XyoPipeCreatorBase : XYBase() {
    private val listeners = HashMap<String, XyoBluetoothPipeCreatorListener>()
    protected var canCreate = false

    abstract fun start (procedureCatalogueInterface: XyoNetworkProcedureCatalogueInterface)

    /**
     * Stop the creator from creating any more connections.
     */
    open fun stop () {
        canCreate = false
    }

    protected fun onCreateConnection (connection: XyoNetworkPipe) {
        for ((_, listener) in listeners) {
            listener.onCreatedConnection(connection)
        }
    }

    fun addListener(key : String, listener : XyoBluetoothPipeCreatorListener) {
        listeners[key] = listener
    }

    fun removeListener(key : String) {
        listeners.remove(key)
    }
}