package network.xyo.sdk.ble.sample.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.pending_bound_witness_fragment.*
import kotlinx.android.synthetic.main.pending_bound_witness_fragment.view.*
import network.xyo.sdk.ble.sample.R
import network.xyo.sdkcorekotlin.boundWitness.XyoBoundWitness
import network.xyo.sdkcorekotlin.node.XyoNodeListener

class XyoPendingBoundWitnessFragment : Fragment() {
    private var isDone = false

    val listener = object : XyoNodeListener() {
        override fun onBoundWitnessEndFailure(error: Exception?) {
            ui {
                pb_circle_bw.visibility = View.GONE
                tx_bw_error.text = error?.message ?: error.toString()
            }
        }

        override fun onBoundWitnessEndSuccess(boundWitness: XyoBoundWitness) {
            ui {
                isDone = true
                pb_circle_bw.visibility = View.GONE
                tx_bw_error.text = getString(R.string.done)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.pending_bound_witness_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!isDone) {
            view.pb_circle_bw.visibility = View.VISIBLE
        } else {
            view.pb_circle_bw.visibility = View.GONE
        }
    }

    companion object {
        fun newInstance () : XyoPendingBoundWitnessFragment {
            return XyoPendingBoundWitnessFragment()
        }
    }
}