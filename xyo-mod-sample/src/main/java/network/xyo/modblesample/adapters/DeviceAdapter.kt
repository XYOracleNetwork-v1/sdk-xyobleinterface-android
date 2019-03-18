package network.xyo.modblesample.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
            view.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            holder.properties.addView(view)
        }

        if (list[position] is XyoBluetoothClient) {
            holder.boundWitnessButton.isEnabled = true

            holder.boundWitnessButton.setOnClickListener {
                listener?.onClick(list[position])
            }

            return
        }

        holder.boundWitnessButton.isEnabled = false
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

    /**
     * Gets the list of current items in the list
     *
     * @return The list of deviceAdapter
     */
    fun getList(): ArrayList<XYBluetoothDevice> {
        return list
    }

    /**
     * Adds an item to the list
     *
     * @param item The item to add
     */
    fun addItem(item: XyoBluetoothClient) {
        list.add(item)
        notifyDataSetChanged()
    }

    /**
     * Adda a group of items to the list at once.
     *
     * @param items The list of items to add.
     */
    fun addItems (items : Array<XYBluetoothDevice>) {
        for (item in items) {
            list.add(item)
        }

        notifyDataSetChanged()
    }

    /**
     * Changes ALL of the items in the list.
     *
     * @param items The items to add.
     */
    fun setItems (items : Array<XYBluetoothDevice>) {
        list =  ArrayList(items.toList())
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView){
        val name : TextView = itemView.findViewById(R.id.tx_device_name)
        val mac : TextView = itemView.findViewById(R.id.tx_mac)
        val boundWitnessButton : Button = itemView.findViewById(R.id.btn_bw)
        val typeOfDevice : TextView = itemView.findViewById(R.id.tx_type_of_device)
        val properties : LinearLayout = itemView.findViewById(R.id.ll_properties)
    }

    interface XYServiceListAdapterListener {
        fun onClick(device : XYBluetoothDevice)
    }
}

