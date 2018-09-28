package network.xyo.mod_bluetooth_kotlin

import network.xyo.sdkcorekotlin.network.XyoNetworkPipe

interface XyoBluetoothConnectionListener {
    /**
     * This connection will be called whenever a connection request has failed (disconnect).
     */
    fun onConnectionFail ()

    /**
     * This method will be called before a connection is tried to be established.
     */
    fun onConnectionRequest ()

    /**
     * This method will be called every time a pipe is established.
     *
     * @param pipe The pipe that was created with the connection.
     */
    fun onCreated (pipe : XyoNetworkPipe)
}