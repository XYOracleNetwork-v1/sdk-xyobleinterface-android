package network.xyo.sdk.ble.sample.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.bound_witness_fragment.*
import kotlinx.coroutines.runBlocking
import network.xyo.sdk.ble.sample.R
import network.xyo.sdk.ble.sample.adapters.BoundWitnessAdapter
import network.xyo.sdkcorekotlin.boundWitness.XyoBoundWitness
import network.xyo.sdkcorekotlin.boundWitness.XyoBoundWitnessVerify
import network.xyo.sdkcorekotlin.hashing.XyoBasicHashBase
import network.xyo.sdkcorekotlin.schemas.XyoSchemas
import network.xyo.sdkobjectmodelkotlin.toHexString

class XyoBoundWitnessFragment : Fragment() {
    interface BoundWitnessItemResolver {
        fun getItem (boundWitness: XyoBoundWitness) : BoundWitnessAdapter.XyoBoundWitnessItem?
    }

    val jsonResolver = object : BoundWitnessItemResolver {
        override fun getItem(boundWitness: XyoBoundWitness): BoundWitnessAdapter.XyoBoundWitnessItem? {
            val key = "JSON"
            val value = boundWitness.toString()
            return BoundWitnessAdapter.XyoBoundWitnessItem(key, value)
        }
    }

    val bytesResolver = object : BoundWitnessItemResolver {
        override fun getItem(boundWitness: XyoBoundWitness): BoundWitnessAdapter.XyoBoundWitnessItem? {
            return BoundWitnessAdapter.XyoBoundWitnessItem("Bytes", boundWitness.bytesCopy.toHexString())
        }
    }

    val hashResolver = object : BoundWitnessItemResolver {
        override fun getItem(boundWitness: XyoBoundWitness): BoundWitnessAdapter.XyoBoundWitnessItem? {
            return runBlocking {
                val hash = boundWitness.getHash(XyoBasicHashBase.createHashType(XyoSchemas.SHA_256, "SHA-256")).await().bytesCopy.toHexString()
                return@runBlocking BoundWitnessAdapter.XyoBoundWitnessItem("SHA 256 Hash", hash)
            }
        }
    }

    val numberOfPartiesResolver = object : BoundWitnessItemResolver {
        override fun getItem(boundWitness: XyoBoundWitness): BoundWitnessAdapter.XyoBoundWitnessItem? {
            return BoundWitnessAdapter.XyoBoundWitnessItem("Parties", boundWitness.numberOfParties.toString())
        }
    }

    val isValidResolver = object : BoundWitnessItemResolver {
        override fun getItem(boundWitness: XyoBoundWitness): BoundWitnessAdapter.XyoBoundWitnessItem? {
            return runBlocking {
                val validator = XyoBoundWitnessVerify(false)
                val result = validator.verify(boundWitness).await()
                return@runBlocking  BoundWitnessAdapter.XyoBoundWitnessItem("Crypto Validity", result.toString())
            }
        }
    }

    private val adapter = BoundWitnessAdapter()
    private val itemResolvers = object : ArrayList<BoundWitnessItemResolver>() {
        init {
            add(hashResolver)
            add(numberOfPartiesResolver)
            add(jsonResolver)
            add(bytesResolver)
            add(isValidResolver)
        }
    }
    lateinit var boundWitness : XyoBoundWitness


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bound_witness_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initAdapter()
        updateItems(getItems(boundWitness))
    }

    private fun getItems (boundWitness: XyoBoundWitness) : Array<BoundWitnessAdapter.XyoBoundWitnessItem> {
        val returnItems = ArrayList<BoundWitnessAdapter.XyoBoundWitnessItem>()

        for (resolver in itemResolvers) {
            val resolved = resolver.getItem(boundWitness)

            if (resolved != null) {
                returnItems.add(resolved)
            }
        }

        return returnItems.toTypedArray()
    }

    private fun updateItems (items : Array<BoundWitnessAdapter.XyoBoundWitnessItem>) {
        ui {
            adapter.setItems(items)
            adapter.notifyDataSetChanged()
        }
    }

    private fun initAdapter () {
        val adapterView = rv_bw_list
        val layoutManager = LinearLayoutManager(this.context, RecyclerView.VERTICAL, false)
        adapterView.layoutManager = layoutManager
        adapterView.adapter = adapter
    }

    companion object {
        fun newInstance (boundWitness : XyoBoundWitness) : XyoBoundWitnessFragment {
            val frag = XyoBoundWitnessFragment()
            frag.boundWitness = boundWitness
            return frag
        }
    }
}