package network.xyo.sdk.ble.sample


import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import kotlinx.coroutines.*
import network.xyo.ble.devices.apple.XYIBeaconBluetoothDevice
import network.xyo.ble.generic.devices.XYBluetoothDevice
import network.xyo.ble.generic.gatt.peripheral.XYBluetoothResult
import network.xyo.ble.generic.scanner.XYSmartScan
import network.xyo.ble.generic.scanner.XYSmartScanModern
import network.xyo.sdk.ble.sample.fragments.*
import network.xyo.modbluetoothkotlin.XyoBleSdk
import network.xyo.modbluetoothkotlin.client.*
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
import kotlin.collections.ArrayList


/**
 * The main activity of the sample application to test the functionality of mod-ble-android. This app should be able
 * to do bound witnesses over bluetooth with other XYO Enabled deviceAdapter. Including other instances of this app. This
 * app is not recommended to be used as a production XYO Enabled Device but rather a tool for development because
 * that state of the node will not persist.
 */
@kotlin.ExperimentalUnsignedTypes
class MainActivity : FragmentActivity() {
    private var shouldBridge = false
    private lateinit var scanner: XYSmartScanModern
    private lateinit var node: XyoRelayNode

    private val serverCallback = object : XyoBluetoothServer.Listener {
        override fun onPipe(pipe: XyoNetworkPipe) {
            GlobalScope.launch {
                val handler = XyoNetworkHandler(pipe)

                node.boundWitness(handler, boundWitnessCatalog).await()
                return@launch
            }
        }
    }

    private val boundWitnessCatalog = object : XyoProcedureCatalog {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        GlobalScope.launch {
            initScanner()
            initServer(this@MainActivity)
        }

        val hasher = XyoBasicHashBase.createHashType(XyoSchemas.SHA_256, "SHA-256")
        val storage = XyoInMemoryStorageProvider()
        val blockRepo = XyoStorageOriginBlockRepository(storage, hasher)
        val stateRepo = XyoStorageOriginStateRepository(storage)
        val queueRepo = XyoStorageBridgeQueueRepository(storage)
        node = XyoRelayNode(blockRepo, stateRepo, queueRepo, hasher)

        showDevicesFragment()

        runBlocking {
            node.selfSignOriginChain().await()
            node.addListener(this.toString(), nodeListener)
        }
    }

    private suspend fun initScanner() {
        XyoBluetoothClient.enable(true)
        XYIBeaconBluetoothDevice.enable(true)
        XyoSentinelX.enable(true)
        XyoBridgeX.enable(true)
        XyoAndroidAppX.enable(true)
        XyoIosAppX.enable(true)
        XyoSha256WithSecp256K.enable()
        scanner = createNewScanner()
        try {
            scanner.start()
        } catch(ex: Exception) {
            Log.e("", ex.stackTrace.toString())
        }
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

    private suspend fun initServer(context: Context): Boolean {
        val advertiserResult = XyoBleSdk.advertiser(context).startAdvertiser()
        if (advertiserResult?.hasError() == true) {
            return false
        }
        XyoBleSdk.server(context).listener = serverCallback
        return true
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

        override fun onBoundWitnessEndFailure(error: Exception?) {
            Log.e("Xyo", error.toString())
            super.onBoundWitnessEndFailure(error)
        }
    }


    private suspend fun tryBoundWitness(device: XyoBluetoothClient): XYBluetoothResult<XyoBoundWitness> {
        Log.i(TAG, "tryBoundWitness: Started")
        return device.connection {
            Log.i(TAG, "tryBoundWitness: Connected")
            val pipe = device.createPipe()

            Log.i(TAG, "tryBoundWitness: CreatePipe: $pipe")

            if (pipe != null) {
                val handler = XyoNetworkHandler(pipe)

                Log.i(TAG, "tryBoundWitness: Starting BW")
                val bw = node.boundWitness(handler, boundWitnessCatalog).await()
                Log.i(TAG, "tryBoundWitness: Completing BW")
                if (bw != null) {
                    return@connection XYBluetoothResult(bw)
                }
            }

            return@connection XYBluetoothResult<XyoBoundWitness>(null, XYBluetoothResult.ErrorCode.Unknown)
        }
    }

    companion object {
        const val TAG = "MainActivity"
    }
}
