package network.xyo.sdk.ble.sample.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import network.xyo.sdk.ble.sample.R
import network.xyo.sdkcorekotlin.boundWitness.XyoBoundWitness
import network.xyo.sdkcorekotlin.hashing.XyoBasicHashBase
import network.xyo.sdkcorekotlin.schemas.XyoSchemas
import network.xyo.sdkobjectmodelkotlin.toHexString

class HashAdapter (var listener : Listener?) : RecyclerView.Adapter<HashAdapter.ViewHolder>() {
    private val hasher = XyoBasicHashBase.createHashType(XyoSchemas.SHA_256, "SHA-256")
    private var list: ArrayList<XyoBoundWitness> = arrayListOf()

    interface Listener  {
        fun onSelected (boundWitness: XyoBoundWitness)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        runBlocking {
            val item = list[position]
            holder.hash.text = item.getHash(hasher).await().bytesCopy.toHexString()

            holder.hash.setOnClickListener {
                listener?.onSelected(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.hash_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int {
        return list.size
    }

    fun getList(): ArrayList<XyoBoundWitness> {
        return list
    }

    fun addItem(item: XyoBoundWitness) {
        list.add(item)
        notifyDataSetChanged()
    }

    fun addItems (items : Array<XyoBoundWitness>) {
        for (item in items) {
            list.add(item)
        }

        notifyDataSetChanged()
    }

    fun setItems (items : Array<XyoBoundWitness>) {
        list =  ArrayList(items.toList())
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView){
        val hash : TextView = itemView.findViewById(R.id.tx_bw_hash)
    }
}