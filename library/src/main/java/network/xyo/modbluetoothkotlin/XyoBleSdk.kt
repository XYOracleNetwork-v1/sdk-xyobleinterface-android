package network.xyo.modbluetoothkotlin

import android.content.Context
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import network.xyo.ble.generic.gatt.server.XYBluetoothAdvertiser
import network.xyo.ble.generic.gatt.server.XYBluetoothGattServer
import network.xyo.modbluetoothkotlin.advertiser.XyoBluetoothAdvertiser
import network.xyo.modbluetoothkotlin.server.XyoBluetoothServer
import java.util.*

@kotlin.ExperimentalUnsignedTypes
class XyoBleSdk {
    companion object {
        private var server: XyoBluetoothServer? = null
        private var advertiser: XyoBluetoothAdvertiser? = null
        private val initServerMutex = Mutex(false)
        private val initAdvertiserMutex = Mutex(false)

        private fun createNewAdvertiser(context: Context, major: UShort?, minor: UShort?): XyoBluetoothAdvertiser {
            val newAdvertiser = XyoBluetoothAdvertiser(
                    major ?: Random().nextInt(Short.MAX_VALUE * 2 + 1).toUShort(),
                    minor ?: Random().nextInt(Short.MAX_VALUE * 2 + 1).toUShort(),
                    XYBluetoothAdvertiser(context))
            newAdvertiser.configureAdvertiser()
            advertiser = newAdvertiser
            return newAdvertiser
        }

        private suspend fun initServer(context: Context): XyoBluetoothServer = GlobalScope.async {
            val newServer =  XyoBluetoothServer(XYBluetoothGattServer(context))
            newServer.initServer()
            server = newServer
            return@async newServer
        }.await()

        suspend fun server(context: Context): XyoBluetoothServer = GlobalScope.async {
            initServerMutex.lock(this)
            val result = server ?: initServer(context)
            initServerMutex.unlock(this)
            return@async result
        }.await()

        suspend fun advertiser(context: Context, major: UShort? = null, minor: UShort? = null): XyoBluetoothAdvertiser = GlobalScope.async {
            initAdvertiserMutex.lock(this)
            val result = advertiser ?: createNewAdvertiser(context, major, minor)
            initAdvertiserMutex.unlock(this)
            return@async result
        }.await()
    }
}