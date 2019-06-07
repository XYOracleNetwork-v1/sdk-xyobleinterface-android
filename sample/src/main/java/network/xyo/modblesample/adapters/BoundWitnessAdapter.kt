package network.xyo.modblesample.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import network.xyo.modblesample.R

class BoundWitnessAdapter : RecyclerView.Adapter<BoundWitnessAdapter.ViewHolder>() {
    private var list: ArrayList<XyoBoundWitnessItem> = arrayListOf()

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.key.text = item.key
        holder.value.text = item.value
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.bound_witness_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int {
        return list.size
    }

    fun getList(): ArrayList<XyoBoundWitnessItem> {
        return list
    }

    fun addItem(item: XyoBoundWitnessItem) {
        list.add(item)
        notifyDataSetChanged()
    }

    fun addItems (items : Array<XyoBoundWitnessItem>) {
        for (item in items) {
            list.add(item)
        }

        notifyDataSetChanged()
    }

    fun setItems (items : Array<XyoBoundWitnessItem>) {
        list =  ArrayList(items.toList())
        notifyDataSetChanged()
    }

    class XyoBoundWitnessItem(val key : String, val value : String)

    class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView){
        val key : TextView = itemView.findViewById(R.id.tx_bw_item_name)
        val value : TextView = itemView.findViewById(R.id.tx_bw_item_content)
    }
}