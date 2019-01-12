package network.xyo.modbluetoothkotlin

/**
 * The Listener for a XyoBluetoothPipeCreator. This is used to know when a connection has been
 * established.
 */
interface XyoBluetoothPipeCreatorListener {

    /**
     * This will be called when a connection has been established.
     *
     * @param connection The connection that was created.
     */
    fun onCreatedConnection (connection: XyoBluetoothConnection)
}