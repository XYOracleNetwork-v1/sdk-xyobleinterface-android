package network.xyo.modblesample.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.modblesample.R
import network.xyo.modbluetoothkotlin.client.XyoBluetoothClient
import network.xyo.modbluetoothkotlin.client.XyoSentinelX

/**
 * The Recycler View adapter for the list of nearby deviceAdapter.
 *
 * @param listener The listener for on bw click events.
 */
class DeviceAdapter (var listener : XYServiceListAdapterListener?) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
    private var list: ArrayList<XYBluetoothDevice> = arrayListOf()

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.properties.removeAllViews()
        holder.name.text = list[position].name ?: "Unknown"
        holder.mac.text = list[position].address
        holder.typeOfDevice.text = list[position].javaClass.simpleName

        for (property in getProperties(list[position])) {
            val view = TextView(holder.itemView.context)
            view.text = property
            view.setTextColor(Color.parseColor("#ffffff"))
            view.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            holder.properties.addView(view)
        }

        holder.card.setOnClickListener {
            listener?.onClick(list[position])
        }
    }

    private fun getProperties (device: XYBluetoothDevice) : Array<String> {
        if (device is XyoSentinelX) {
            return arrayOf("Claimed: ${device.isClaimed()}")
        }

        return arrayOf()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.device_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int {
        return list.size
    }

    fun getList(): ArrayList<XYBluetoothDevice> {
        return list
    }

    fun addItem(item: XyoBluetoothClient) {
        list.add(item)
        notifyDataSetChanged()
    }

    fun addItems (items : Array<XYBluetoothDevice>) {
        for (item in items) {
            list.add(item)
        }

        notifyDataSetChanged()
    }

    fun setItems (items : Array<XYBluetoothDevice>) {
        list =  ArrayList(items.toList())
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView){
        val name : TextView = itemView.findViewById(R.id.tx_device_name)
        val mac : TextView = itemView.findViewById(R.id.tx_mac)
        val typeOfDevice : TextView = itemView.findViewById(R.id.tx_type_of_device)
        val properties : LinearLayout = itemView.findViewById(R.id.ll_properties)
        val card : CardView = itemView.findViewById(R.id.cv_device)
    }

    interface XYServiceListAdapterListener {
        fun onClick(device : XYBluetoothDevice)
    }
}

