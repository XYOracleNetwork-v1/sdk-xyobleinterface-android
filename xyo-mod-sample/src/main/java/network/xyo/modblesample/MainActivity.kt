package network.xyo.modblesample


import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import network.xyo.modblesample.R
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
import network.xyo.modblesample.fragments.XyoDevicesFragment
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


/**
 * The main activity of the sample application to test the functionality of mod-ble-android. This app should be able
 * to do bound witnesses over bluetooth with other XYO Enabled deviceAdapter. Including other instances of this app. This
 * app is not recommended to be used as a production XYO Enabled Device but rather a tool for development because
 * that state of the node will not persist.
 */
class MainActivity : FragmentActivity() {
    private lateinit var clientFinder: XyoBluetoothClientCreator
    private lateinit var scanner: XYSmartScanModern
    private lateinit var deviceList: DeviceAdapter
    private lateinit var server: XyoBluetoothServer
    private lateinit var advertiser: XyoBluetoothAdvertiser

    private fun showProgressBar () = runOnUiThread {
        pb_container.bringToFront()
        pb_container.visibility = View.VISIBLE
    }

    private fun hideProgressBar () = runOnUiThread {
        pb_container.visibility = View.GONE
    }

    /**
     * The catalogue of the node to advertise with.
     */
    private val boundWitnessCatalogue = object : XyoNetworkProcedureCatalogueInterface {
        override fun canDo(byteArray: ByteArray): Boolean {
            return ByteBuffer.wrap(byteArray).int and 1 != 0
        }

        override fun getEncodedCanDo(): ByteArray {
            return byteArrayOf(0x00, 0x00, 0x00, 0x01)
        }
    }

    /**
     * Create a new scanner with the context of the activity.
     *
     * @return The created scanner.
     */
    private fun createNewScanner () : XYSmartScanModern {
        return XYSmartScanModern(this)
    }

    /**
     * Create a new advertiser with the context of the activity.
     *
     * @return The created advertiser.
     */
    private fun createNewAdvertiser () : XyoBluetoothAdvertiser {
        return XyoBluetoothAdvertiser(
                Random().nextInt(Short.MAX_VALUE + 1).toShort(),
                Random().nextInt(Short.MAX_VALUE + 1).toShort(),
                XYBluetoothAdvertiser(this))
    }

    /**
     * Create a new server with the context of the activity.
     *
     * @return The created bluetooth server.
     */
    private fun createNewServer () : XyoBluetoothServer {
        return  XyoBluetoothServer(XYBluetoothGattServer(this))
    }

    /**
     * The node being used to do bound witnesses, please note that the state of the node will not persist after
     * the activity reloads.
     */
    private val node = object : XyoOriginChainCreator(XyoInMemoryStorageProvider(), XyoBasicHashBase.createHashType(XyoSchemas.SHA_256, "SHA-256")) {
        override fun getChoice(catalog: Int, strict: Boolean): Int {
            return 1
        }

        init {
            this.originState.addSigner(XyoStubSigner())
        }

        /**
         * Tries to create a bound witness with the given pipe and will update the UI accordingly.
         */
        suspend fun tryBoundWitnessPipe(pipe: XyoNetworkPipe) = GlobalScope.launch {

            addListener("bw_client", object : XyoNodeListener() {
                override fun onBoundWitnessEndFailure(error: Exception?) {
                    hideProgressBar()
                    removeListener("bw")
                }

                override fun onBoundWitnessEndSuccess(boundWitness: XyoBoundWitness) {
                    hideProgressBar()
                    removeListener("bw")
                }

            })

            doBoundWitness(pipe.initiationData, pipe)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestBluetooth(bluetoothPermissionHandler)
        showDevicesFragment()
    }



    private val bluetoothPermissionHandler = object : PermissionHandler() {
        override fun onGranted() {
            initScanner()
            initServer()
            XyoBluetoothClient.enable(true)
            XyoSentinelX.enable(true)
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
    }

    private val deviceHandler = object : XyoDevicesFragment.XyoDevicesFragmentHandler {
        override fun getDevices(): Array<XYBluetoothDevice> {
            return scanner.devices.values.toTypedArray()
        }
    }

    private fun showDevicesFragment () {
        val trans = supportFragmentManager.beginTransaction()
        val frag = XyoDevicesFragment()
        frag.deviceHandler = deviceHandler
        trans.add(R.id.fragment_root, frag)
        trans.commit()
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

    private val serverListener = object : XyoBluetoothPipeCreatorListener {
        override fun onCreatedConnection(connection: XyoBluetoothConnection) {
            showProgressBar()

            connection.addListener(connection.toString(), object : XyoBluetoothConnectionListener {
                override fun onConnectionRequest() {}
                override fun onConnectionFail() {
                    hideProgressBar()
                }

                override fun onCreated(pipe: XyoNetworkPipe) {
                    GlobalScope.launch {


                        node.tryBoundWitnessPipe(pipe)
                    }
                }
            })
        }
    }

    private val onDeviceTry = object : DeviceAdapter.XYServiceListAdapterListener {
        override fun onClick(device: XYBluetoothDevice) {
            GlobalScope.launch {
                if (device is XyoBluetoothClient) {
                    showProgressBar()

                    GlobalScope.launch {
                        val pipe = device.createPipe(boundWitnessCatalogue).await() as? XyoBluetoothClient.XyoBluetoothClientPipe

                        if (pipe != null) {
                            node.addHeuristic("rssi", object : XyoHeuristicGetter {
                                override fun getHeuristic(): XyoBuff? {
                                    val rssi = pipe.rssi?.toByte() ?: return null

                                    return XyoBuff.newInstance(XyoSchemas.RSSI, byteArrayOf(rssi))
                                }
                            })

                            node.tryBoundWitnessPipe(pipe)

                            node.removeHeuristic("rssi")

                        } else {
                           hideProgressBar()
                        }
                    }
                }
            }
        }
    }
}
