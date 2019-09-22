package network.xyo.sdk.ble.sample.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.devices_fragment.*
import kotlinx.android.synthetic.main.devices_fragment.view.*
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.sdk.ble.sample.adapters.DeviceAdapter
import network.xyo.sdk.ble.sample.R


@kotlin.ExperimentalUnsignedTypes
class XyoDevicesFragment : Fragment() {
    interface XyoDevicesFragmentHandler {
        fun getDevices () : Array<XYBluetoothDevice>
        fun onDeviceSelected (device : XYBluetoothDevice)
        fun onHashButtonPress ()
        fun onBridgeChange (bridge : Boolean)
    }

    private var deviceAdapter = DeviceAdapter(null)
    var deviceHandler : XyoDevicesFragmentHandler? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.devices_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initAdapter()
        initRefreshListener()

        view.btn_show_hashes.setOnClickListener {
            deviceHandler?.onHashButtonPress()
        }

        sw_bridge.setOnCheckedChangeListener { _, isChecked ->
            deviceHandler?.onBridgeChange(isChecked)
        }
    }

    private fun initAdapter () {
        val adapterView = rv_device_list
        val layoutManager = LinearLayoutManager(this.context, RecyclerView.VERTICAL, false)
        adapterView.layoutManager = layoutManager
        adapterView.adapter = deviceAdapter

        deviceAdapter.listener = object : DeviceAdapter.XYServiceListAdapterListener {
            override fun onClick(device: XYBluetoothDevice) {
                deviceHandler?.onDeviceSelected(device)
            }
        }
    }

    private fun initRefreshListener () {
        sw_devices.setOnRefreshListener {
            deviceAdapter.setItems(deviceHandler?.getDevices() ?: arrayOf())
            deviceAdapter.notifyDataSetChanged()
            sw_devices.isRefreshing = false
        }
    }
}