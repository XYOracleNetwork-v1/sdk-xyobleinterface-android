package network.xyo.modblesample.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.device_fragment.*
import kotlinx.android.synthetic.main.device_fragment.view.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.gatt.peripheral.XYBluetoothResult
import network.xyo.modblesample.R
import network.xyo.modbluetoothkotlin.client.XyoBluetoothClient

@kotlin.ExperimentalUnsignedTypes
class XyoStandardDeviceFragment : Fragment() {
    lateinit var device : XYBluetoothDevice
    lateinit var listener : Listener

    interface Listener {
        fun onBoundWitness(device: XyoBluetoothClient)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.device_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.tx_device_name_title.text = device.name ?: "Unknown"
        view.tx_device_type.text = device.javaClass.simpleName
        view.tx_device_rssi.text = "RSSI: ${device.rssi.toString()}"
        view.tx_device_publickey.text = "--"

        device.addListener(this.toString(), object : XYBluetoothDevice.Listener() {
            override fun detected(device: XYBluetoothDevice) {
                ui {
                    view.tx_device_rssi.text = "RSSI: ${device.rssi.toString()}"
                }
            }
        })

        if (device is XyoBluetoothClient) {
            btn_bw.setOnClickListener {
                listener.onBoundWitness(device as XyoBluetoothClient)
            }
            btn_getPublicKey.setOnClickListener {
                GlobalScope.launch {
                    ui {
                        view.tx_device_publickey.text = ""
                    }
                    val xyoDevice = device as XyoBluetoothClient
                    val result = device.connection {
                        return@connection xyoDevice.getPublicKey().await()
                    }
                    val outerResult = result.await()
                    val newValue = outerResult.error?.message ?: outerResult.value?.joinToString() ?: "??"
                    ui {
                        view.tx_device_publickey.text = newValue
                    }
                }
            }
        } else {
            btn_bw.visibility = View.GONE
        }
    }

    companion object {
        fun newInstance (device: XYBluetoothDevice, listener: Listener) : XyoStandardDeviceFragment {
            val frag = XyoStandardDeviceFragment()
            frag.device = device
            frag.listener = listener
            return frag
        }
    }
}