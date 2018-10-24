package network.xyo.mod_bluetooth_kotlin

import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import java.util.HashMap

/**
 * A class to manage a bluetooth connection that is creating a pipe.
 */
class XyoBluetoothConnection {
    private val listeners = HashMap<String, XyoBluetoothConnectionListener>()

    /**
     * If the connection is still waiting for a pipe.
     */
    var waiting = true

    /**
     * The pipe with the connection. This value is null if in the process of creating a pipe.
     */
    var pipe : XyoNetworkPipe? = null

    fun onTry () {
        for ((_, listener) in listeners) {
            listener.onConnectionRequest()
        }
    }

    fun onFail () {
        for ((_, listener) in listeners) {
            listener.onConnectionFail()
        }
    }

    fun onCreate (pipe : XyoNetworkPipe) {
        waiting = false
        for ((_, listener) in listeners) {
            listener.onCreated(pipe)
        }
    }

    fun addListener(key : String, listener : XyoBluetoothConnectionListener) {
        listeners[key] = listener
    }

    fun removeListener(key : String) {
        listeners.remove(key)
    }
}