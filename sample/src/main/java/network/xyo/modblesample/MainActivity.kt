package network.xyo.modblesample


import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.devices.XYIBeaconBluetoothDevice
import network.xyo.ble.gatt.peripheral.XYBluetoothError
import network.xyo.ble.gatt.peripheral.XYBluetoothResult
import network.xyo.ble.gatt.server.XYBluetoothAdvertiser
import network.xyo.ble.gatt.server.XYBluetoothGattServer
import network.xyo.ble.scanner.XYSmartScan
import network.xyo.ble.scanner.XYSmartScanModern
import network.xyo.modblesample.fragments.*
import network.xyo.modbluetoothkotlin.advertiser.XyoBluetoothAdvertiser
import network.xyo.modbluetoothkotlin.client.XyoBluetoothClient
import network.xyo.modbluetoothkotlin.client.XyoSentinelX
import network.xyo.modbluetoothkotlin.server.XyoBluetoothServer
import network.xyo.sdkcorekotlin.boundWitness.XyoBoundWitness
import network.xyo.sdkcorekotlin.crypto.signing.ecdsa.secp256k.XyoSha256WithSecp256K
import network.xyo.sdkcorekotlin.hashing.XyoBasicHashBase
import network.xyo.sdkcorekotlin.network.XyoNetworkHandler
import network.xyo.sdkcorekotlin.network.XyoNetworkPipe
import network.xyo.sdkcorekotlin.network.XyoProcedureCatalog
import network.xyo.sdkcorekotlin.node.XyoNodeListener
import network.xyo.sdkcorekotlin.node.XyoRelayNode
import network.xyo.sdkcorekotlin.persist.XyoInMemoryStorageProvider
import network.xyo.sdkcorekotlin.persist.repositories.XyoStorageBridgeQueueRepository
import network.xyo.sdkcorekotlin.persist.repositories.XyoStorageOriginBlockRepository
import network.xyo.sdkcorekotlin.persist.repositories.XyoStorageOriginStateRepository
import network.xyo.sdkcorekotlin.schemas.XyoSchemas
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.ArrayList


/**
 * The main activity of the sample application to test the functionality of mod-ble-android. This app should be able
 * to do bound witnesses over bluetooth with other XYO Enabled deviceAdapter. Including other instances of this app. This
 * app is not recommended to be used as a production XYO Enabled Device but rather a tool for development because
 * that state of the node will not persist.
 */
class MainActivity : FragmentActivity() {
    private var shouldBridge = false
    private lateinit var scanner: XYSmartScanModern
    private lateinit var server: XyoBluetoothServer
    private lateinit var advertiser: XyoBluetoothAdvertiser
    private lateinit var node: XyoRelayNode

    private val serverCallback = object : XyoBluetoothServer.Listener {
        override fun onPipe(pipe: XyoNetworkPipe) {
            GlobalScope.launch {
                val handler = XyoNetworkHandler(pipe)

                node.boundWitness(handler, boundWitnessCatalogue).await()
                return@launch
            }
        }
    }

    private val boundWitnessCatalogue = object : XyoProcedureCatalog {
        override fun canDo(byteArray: ByteArray): Boolean {
            if (shouldBridge) {
                return true
            }

            return ByteBuffer.wrap(byteArray).int and 1 != 0
        }

        override fun choose(byteArray: ByteArray): ByteArray {
            return byteArrayOf(0x00, 0x00, 0x00, 0x01)
        }

        override fun getEncodedCanDo(): ByteArray {
            if (shouldBridge) {
                return byteArrayOf(0x00, 0x00, 0x00, 0xff.toByte())
            }

            return byteArrayOf(0x00, 0x00, 0x00, 0x01)
        }
    }

    private fun createNewScanner(): XYSmartScanModern {
        return XYSmartScanModern(this)
    }

    private fun createNewAdvertiser(): XyoBluetoothAdvertiser {
        return XyoBluetoothAdvertiser(
                Random().nextInt(Short.MAX_VALUE + 1).toShort(),
                Random().nextInt(Short.MAX_VALUE + 1).toShort(),
                XYBluetoothAdvertiser(this))
    }

    private fun createNewServer(): XyoBluetoothServer {
        return XyoBluetoothServer(XYBluetoothGattServer(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestBluetooth(bluetoothPermissionHandler)

        val hasher = XyoBasicHashBase.createHashType(XyoSchemas.SHA_256, "SHA-256")
        val sotrage = XyoInMemoryStorageProvider()
        val blockRepo = XyoStorageOriginBlockRepository(sotrage, hasher)
        val stateRepo = XyoStorageOriginStateRepository(sotrage)
        val queueRepo = XyoStorageBridgeQueueRepository(sotrage)
        node = XyoRelayNode(blockRepo, stateRepo, queueRepo, hasher)

        showDevicesFragment()

        runBlocking {
            node.selfSignOriginChain().await()
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

    private fun requestBluetooth(handler: PermissionHandler) {
        Permissions.check(this, android.Manifest.permission.ACCESS_COARSE_LOCATION, "Need it.", handler)
    }

    private fun initScanner() = GlobalScope.launch {
        XyoBluetoothClient.enable(true)
        XYIBeaconBluetoothDevice.enable(true)
        XyoSentinelX.enable(true)
        scanner = createNewScanner()
        scanner.start().await()
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
                val bw = tryBoundWitness(device)

                if (bw.value == null) {
                    listen.onBoundWitnessEndFailure(java.lang.Exception("Device can not bound witness. ${bw.error}"))
                }
            }
        }
    }

    private fun showDevicesFragment() {
        val trans = supportFragmentManager.beginTransaction()
        val frag = XyoDevicesFragment()
        frag.deviceHandler = deviceHandler
        trans.add(R.id.fragment_root, frag)
        trans.commit()
    }

    private fun showDeviceFragment(device: XYBluetoothDevice) {
        val frag = XyoStandardDeviceFragment.newInstance(device, boundWitnessHandler)
        replaceFragment(frag)
    }

    private fun showPendingBwFragment(): XyoNodeListener {
        val frag = XyoPendingBoundWitnessFragment.newInstance()
        replaceFragment(frag)
        return frag.listener
    }

    private fun showBoundWitnessFragment(boundWitness: XyoBoundWitness) {
        val frag = XyoBoundWitnessFragment.newInstance(boundWitness)
        replaceFragment(frag)
    }

    private fun showHashesFragment(boundWitnesses: Array<XyoBoundWitness>) {
        val frag = XyoHashesFragment.newInstance(boundWitnesses, hashHandler)
        replaceFragment(frag)
    }

    private fun getAllBoundWitnesses(): Array<XyoBoundWitness> {
        return runBlocking {
            val blockHashes = node.blockRepository.getAllOriginBlockHashes().await()
                    ?: return@runBlocking arrayOf<XyoBoundWitness>()
            val returnBlocks = ArrayList<XyoBoundWitness>()

            for (hash in blockHashes) {
                val block = node.blockRepository.getOriginBlockByBlockHash(hash.bytesCopy).await()

                if (block != null) {
                    returnBlocks.add(block)
                }
            }

            returnBlocks.toTypedArray()
        }
    }

    private fun initServer() = GlobalScope.launch {
        server = createNewServer()
        server.initServer().await()
        advertiser = createNewAdvertiser()
        advertiser.configureAdvertiser()
        advertiser.startAdvertiser().await()
        server.listener = serverCallback
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

    private val nodeListener: XyoNodeListener = object : XyoNodeListener() {
        override fun onBoundWitnessEndSuccess(boundWitness: XyoBoundWitness) {
            showBoundWitnessFragment(boundWitness)
        }
    }


    private suspend fun tryBoundWitness(device: XyoBluetoothClient): XYBluetoothResult<XyoBoundWitness> {
        return device.connection {
            val pipe = device.createPipe().await()

            if (pipe != null) {
                val handler = XyoNetworkHandler(pipe)

                val bw = node.boundWitness(handler, boundWitnessCatalogue).await()
                return@connection XYBluetoothResult(bw)
            }

            return@connection XYBluetoothResult<XyoBoundWitness>(null, XYBluetoothError("pipe is null"))
        }.await()
    }
}
