package network.xyo.modblesample.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.modblesample.R
import network.xyo.modbluetoothkotlin.client.XyoBluetoothClient

/**
 * The Recycler View adapter for the list of nearby devices.
 *
 * @param listener The listener for on bw click events.
 */
class DeviceAdapter (private val listener : XYServiceListAdapterListener?) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
    private var list: ArrayList<XYBluetoothDevice> = arrayListOf()

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.name.text = list[position].name ?: "Unknown"
        holder.mac.text = list[position].address

        if (list[position] is XyoBluetoothClient) {
            holder.bwButton.isEnabled = true

            holder.bwButton.setOnClickListener {
                listener?.onClick(list[position])
            }

            return
        }

        holder.bwButton.isEnabled = false

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
     * @return The list of devices
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
        val bwButton : Button = itemView.findViewById(R.id.btn_bw)
    }

    interface XYServiceListAdapterListener {
        fun onClick(device : XYBluetoothDevice)
    }
}

