package com.bluetriangle.android.demo.groupingpoc.tabs

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.android.demo.NetworkPocActivity
import com.bluetriangle.android.demo.R
import com.bluetriangle.android.demo.kotlin.HybridDemoLayoutActivity
import com.bluetriangle.android.demo.kotlin.JankTestActivity
import com.bluetriangle.android.demo.kotlin.ScrollJankTestActivity

class FifthTabFragment : Fragment() {
    override fun onResume() {
        super.onResume()
        Tracker.instance?.setGroupName("Fifth Screen")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_fifth_tab_group, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.hybrid_demo).setOnClickListener {
            startActivity(Intent(context, HybridDemoLayoutActivity::class.java))
        }

        view.findViewById<Button>(R.id.jank_test).setOnClickListener {
            startActivity(Intent(context, JankTestActivity::class.java))
        }

        view.findViewById<Button>(R.id.scroll_jank_test).setOnClickListener {
            startActivity(Intent(context, ScrollJankTestActivity::class.java))
        }

        view.findViewById<Button>(R.id.fatal_anr_button).setOnClickListener {
            Thread.sleep(25000)
        }

        view.findViewById<Button>(R.id.network_tracking_button).setOnClickListener {
            startActivity(Intent(context, NetworkPocActivity::class.java))
        }
    }
}