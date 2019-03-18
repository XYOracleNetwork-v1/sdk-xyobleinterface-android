package network.xyo.modblesample


import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYIBeaconBluetoothDevice
import network.xyo.ble.gatt.server.XYBluetoothAdvertiser
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.scanner.XYSmartScanModern
import network.xyo.modbluetoothkotlin.advertiser.XyoBluetoothAdvertiser
import network.xyo.modbluetoothkotlin.client.XyoBluetoothClient
import network.xyo.modbluetoothkotlin.client.XyoBluetoothClientCreator
import network.xyo.modbluetoothkotlin.server.XyoBluetoothServer
import network.xyo.sdkcorekotlin.boundWitness.XyoBoundWitness
import network.xyo.sdkcorekotlin.network.XyoNetworkProcedureCatalogueInterface
import network.xyo.sdkcorekotlin.node.XyoOriginChainCreator
import network.xyo.sdkcorekotlin.node.XyoNodeListener
import network.xyo.sdkcorekotlin.persist.XyoInMemoryStorageProvider
import java.nio.ByteBuffer
import network.xyo.modblesample.adapters.DeviceAdapter
import network.xyo.modbluetoothkotlin.XyoBluetoothConnection
import network.xyo.modbluetoothkotlin.XyoBluetoothConnectionListener
import network.xyo.modbluetoothkotlin.XyoBluetoothPipeCreatorListener
import network.xyo.modbluetoothkotlin.client.XyoSentinelX
import network.xyo.sdkcorekotlin.crypto.signing.stub.XyoStubSigner
import network.xyo.sdkcorekotlin.hashing.XyoBasicHashBase
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.heuristics.XyoHeuristicGetter
import network.xyo.sdkcorekotlin.schemas.XyoSchemas
import network.xyo.sdkobjectmodelkotlin.buffer.XyoBuff
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import kotlinx.coroutines.runBlocking
import network.xyo.ble.scanner.XYSmartScan
import network.xyo.modblesample.fragments.*
import network.xyo.sdkcorekotlin.crypto.signing.ecdsa.secp256k.XyoSha256WithSecp256K
import kotlin.collections.ArrayList


/**
 * The main activity of the sample application to test the functionality of mod-ble-android. This app should be able
 * to do bound witnesses over bluetooth with other XYO Enabled deviceAdapter. Including other instances of this app. This
 * app is not recommended to be used as a production XYO Enabled Device but rather a tool for development because
 * that state of the node will not persist.
 */
class MainActivity : FragmentActivity() {
    private var shouldBridge = false
    private lateinit var clientFinder: XyoBluetoothClientCreator
    private lateinit var scanner: XYSmartScanModern
    private lateinit var deviceList: DeviceAdapter
    private lateinit var server: XyoBluetoothServer
    private lateinit var advertiser: XyoBluetoothAdvertiser

    private val boundWitnessCatalogue = object : XyoNetworkProcedureCatalogueInterface {
        override fun canDo(byteArray: ByteArray): Boolean {
            if (shouldBridge) {
                return true
            }

            return ByteBuffer.wrap(byteArray).int and 1 != 0
        }

        override fun getEncodedCanDo(): ByteArray {
            if (shouldBridge) {
                return byteArrayOf(0x00, 0x00, 0x00, 0xff.toByte())
            }

            return byteArrayOf(0x00, 0x00, 0x00, 0x01)
        }
    }

    private fun createNewScanner () : XYSmartScanModern {
        return XYSmartScanModern(this)
    }

    private fun createNewAdvertiser () : XyoBluetoothAdvertiser {
        return XyoBluetoothAdvertiser(
                Random().nextInt(Short.MAX_VALUE + 1).toShort(),
                Random().nextInt(Short.MAX_VALUE + 1).toShort(),
                XYBluetoothAdvertiser(this))
    }

    private fun createNewServer () : XyoBluetoothServer {
        return  XyoBluetoothServer(XYBluetoothGattServer(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestBluetooth(bluetoothPermissionHandler)
        showDevicesFragment()

        runBlocking {
            node.selfSignOriginChain(0).await()
            node.addListener(this.toString(), nodeListener)
        }
    }


    private val bluetoothPermissionHandler = object : PermissionHandler() {
        override fun onGranted() {
            initScanner()
            initServer()
            XyoBluetoothClient.enable(true)
            XyoSentinelX.enable(true)
            XyoSha256WithSecp256K.enable()
        }
    }

    private fun requestBluetooth (handler : PermissionHandler) {
        Permissions.check(this, android.Manifest.permission.ACCESS_COARSE_LOCATION, "Need it.", handler)
    }

    private fun initScanner () = GlobalScope.launch {
        XyoBluetoothClient.enable(true)
        XYIBeaconBluetoothDevice.enable(true)
        scanner = createNewScanner()
        scanner.start().await()
        clientFinder = XyoBluetoothClientCreator(scanner)
        scanner.addListener(this.toString(), deviceButtonListener)
    }

    private val deviceHandler = object : XyoDevicesFragment.XyoDevicesFragmentHandler {
        override fun getDevices(): Array<XYBluetoothDevice> {
            return scanner.devices.values.toTypedArray()
        }

        override fun onDeviceSelected(device: XYBluetoothDevice) {
            showDeviceFragment(device)
        }

        override fun onHashButtonPress() {
            showHashesFragment(getAllBoundWitnesses())
        }

        override fun onBridgeChange(bridge: Boolean) {
            shouldBridge = bridge
        }
    }

    private val hashHandler = object : XyoHashesFragment.XyoHashesFragmentListener {
        override fun onSelected(boundWitness: XyoBoundWitness) {
            showBoundWitnessFragment(boundWitness)
        }
    }

    private val boundWitnessHandler = object : XyoStandardDeviceFragment.Listener {
        override fun onBoundWitness(device: XyoBluetoothClient) {
            val listen = showPendingBwFragment()

            GlobalScope.launch {
                val bw = tryBoundWitness(device).await()

                if (bw == null) {
                    listen.onBoundWitnessEndFailure(java.lang.Exception("Device can not bound witness."))
                }
            }
        }
    }

    private fun showDevicesFragment () {
        val trans = supportFragmentManager.beginTransaction()
        val frag = XyoDevicesFragment()
        frag.deviceHandler = deviceHandler
        trans.add(R.id.fragment_root, frag)
        trans.commit()
    }

    private fun showDeviceFragment (device : XYBluetoothDevice) {
        val frag = XyoStandardDeviceFragment.newInstance(device, boundWitnessHandler)
        replaceFragment(frag)
    }

    private fun showPendingBwFragment () : XyoNodeListener {
        val frag = XyoPendingBoundWitnessFragment.newInstance()
        replaceFragment(frag)
        return frag.listener
    }

    private fun showBoundWitnessFragment (boundWitness: XyoBoundWitness) {
        val frag = XyoBoundWitnessFragment.newInstance(boundWitness)
        replaceFragment(frag)
    }

    private fun showHashesFragment (boundWitnesses: Array<XyoBoundWitness>) {
        val frag = XyoHashesFragment.newInstance(boundWitnesses, hashHandler)
        replaceFragment(frag)
    }

    private fun getAllBoundWitnesses () : Array<XyoBoundWitness> {
        return runBlocking {
            val blockHashes = node.originBlocks.getAllOriginBlockHashes().await() ?: return@runBlocking arrayOf<XyoBoundWitness>()
            val returnBlocks = ArrayList<XyoBoundWitness>()

            for (hash in blockHashes) {
                val block = node.originBlocks.getOriginBlockByBlockHash(hash.bytesCopy).await()

                if (block != null) {
                    returnBlocks.add(block)
                }
            }

            returnBlocks.toTypedArray()
        }
    }

    private fun initServer () = GlobalScope.launch {
        server = createNewServer()
        server.spinUpServer().await()
        server.addListener("main", serverListener)
        server.start(boundWitnessCatalogue)
        advertiser = createNewAdvertiser()
        advertiser.configureAdvertiser()
        advertiser.startAdvertiser().await()
    }

    private fun replaceFragment(fragment: Fragment) {
        ui {
            val backStateName = fragment.javaClass.name
            val manager = supportFragmentManager
            val fragmentPopped = manager.popBackStackImmediate(backStateName, 0)

            if (!fragmentPopped && manager.findFragmentByTag(backStateName) == null) {
                val ft = manager.beginTransaction()
                ft.replace(R.id.fragment_root, fragment, backStateName)
                ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                ft.addToBackStack(backStateName)
                ft.commit()
            }
        }
    }


    private val deviceButtonListener = object : XYSmartScan.Listener() {
        override fun detected(device: XYBluetoothDevice) {
            (device as? XyoSentinelX)?.addButtonListener(this.toString(), object : XyoSentinelX.Companion.Listener() {
                override fun onButtonPressed() {
                    showDeviceFragment(device)
                }
            })
        }
    }

    private val nodeListener : XyoNodeListener = object : XyoNodeListener() {
        override fun onBoundWitnessEndSuccess(boundWitness: XyoBoundWitness) {
            showBoundWitnessFragment(boundWitness)
        }
    }

    private val serverListener = object : XyoBluetoothPipeCreatorListener {
        override fun onCreatedConnection(connection: XyoBluetoothConnection) {

            connection.addListener(connection.toString(), object : XyoBluetoothConnectionListener {
                override fun onConnectionRequest() {}
                override fun onConnectionFail() {}

                override fun onCreated(pipe: XyoNetworkPipe) {
                    GlobalScope.launch {
                        node.tryBoundWitnessPipe(pipe)
                    }
                }
            })
        }
    }

    private val node = object : XyoOriginChainCreator(XyoInMemoryStorageProvider(), XyoBasicHashBase.createHashType(XyoSchemas.SHA_256, "SHA-256")) {
        override fun getChoice(catalog: Int, strict: Boolean): Int {
            if (shouldBridge) {
                return 2
            }

            return 1
        }

        init {
            this.originState.addSigner(XyoSha256WithSecp256K.newInstance())
        }

        suspend fun tryBoundWitnessPipe(pipe: XyoNetworkPipe) = suspendCoroutine<XyoBoundWitness?> { cont ->
            GlobalScope.launch {
                addListener("bw_client", object : XyoNodeListener() {
                    override fun onBoundWitnessEndFailure(error: Exception?) {
                        removeListener("bw")
                        cont.resume(null)
                    }

                    override fun onBoundWitnessEndSuccess(boundWitness: XyoBoundWitness) {
                        removeListener("bw")
                        cont.resume(boundWitness)
                    }

                })

                doBoundWitness(pipe.initiationData, pipe)
            }
        }
    }

    private fun tryBoundWitness (device: XyoBluetoothClient) = GlobalScope.async {
            val pipe = device.createPipe(boundWitnessCatalogue).await() as? XyoBluetoothClient.XyoBluetoothClientPipe

            if (pipe != null) {
                node.addHeuristic("rssi", object : XyoHeuristicGetter {
                    override fun getHeuristic(): XyoBuff? {
                        val rssi = pipe.rssi?.toByte() ?: return null

                        return XyoBuff.newInstance(XyoSchemas.RSSI, byteArrayOf(rssi))
                    }
                })

                val bw = node.tryBoundWitnessPipe(pipe)

                node.removeHeuristic("rssi")

                return@async bw

            }

        return@async null
    }
}
