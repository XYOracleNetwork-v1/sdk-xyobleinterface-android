package network.xyo.sdk.ble.sample.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.hashes_fragment.*
import network.xyo.sdk.ble.sample.R
import network.xyo.sdk.ble.sample.adapters.HashAdapter
import network.xyo.sdkcorekotlin.boundWitness.XyoBoundWitness

class XyoHashesFragment : Fragment() {
    private lateinit var boundWitnesses : Array<XyoBoundWitness>
    private var hashAdapter = HashAdapter(null)
    lateinit var hashHandler : XyoHashesFragmentListener

    interface XyoHashesFragmentListener {
        fun onSelected (boundWitness: XyoBoundWitness)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.hashes_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initAdapter()
    }

    private fun initAdapter () {
        val adapterView = rv_hash_list
        val layoutManager = LinearLayoutManager(this.context, RecyclerView.VERTICAL, false)
        adapterView.layoutManager = layoutManager
        adapterView.adapter = hashAdapter
        hashAdapter.setItems(boundWitnesses)

        hashAdapter.listener = object : HashAdapter.Listener {
            override fun onSelected(boundWitness: XyoBoundWitness) {
                hashHandler.onSelected(boundWitness)
            }
        }
    }

    companion object {
        fun newInstance (boundWitnesses : Array<XyoBoundWitness>, listener: XyoHashesFragmentListener) : XyoHashesFragment {
            val frag = XyoHashesFragment()
            frag.boundWitnesses = boundWitnesses
            frag.hashHandler = listener
            return frag
        }
    }
}