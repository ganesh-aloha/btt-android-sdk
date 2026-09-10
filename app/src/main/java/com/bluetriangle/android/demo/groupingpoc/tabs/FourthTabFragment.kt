package com.bluetriangle.android.demo.groupingpoc.tabs

import android.content.Intent
import android.os.Bundle
import android.util.AndroidRuntimeException
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bluetriangle.android.demo.R
import com.bluetriangle.android.demo.compose.ComposeMainActivity
import com.bluetriangle.android.demo.groupingpoc.QuoteRequestHelper
import com.bluetriangle.android.demo.kotlin.HybridDemoLayoutActivity
import com.bluetriangle.android.demo.kotlin.JankTestActivity
import com.bluetriangle.android.demo.kotlin.MemoryTestViewModel.MemoryBlock
import com.bluetriangle.android.demo.kotlin.ScrollJankTestActivity

class FourthTabFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_fourth_tab_group, container, false)
    }

    private var memoryBlock: ArrayList<MemoryBlock>? = null

    fun useMemory() {
        if (memoryBlock == null) {
            memoryBlock = arrayListOf()
        }
        memoryBlock?.add(MemoryBlock())
    }

    fun clearMemory() {
        memoryBlock?.clear()
        memoryBlock = null
        Runtime.getRuntime().gc()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        QuoteRequestHelper.instance.setupQuoteUI(lifecycleScope, view)

        view.findViewById<Button>(R.id.crash_button).setOnClickListener {
            throw AndroidRuntimeException("Manual Crash")
//            Tracker.instance?.trackError(
//                "React Crash Test From Android", "{\"meta\":{\"fVersion\":\"1.0.0\"},\"threads\":[{\"id\":\"2\",\"name\":\"main\",\"crashed\":true,\"caused\":\"android.util.AndroidRuntimeException: Manual Crash\",\"stack\":[{\"i\":0,\"fLine\":\"at com.bluetriangle.android.demo.groupingpoc.tabs.FourthTabFragment.onViewCreated\$lambda$0(FourthTabFragment.kt:51)\"},{\"i\":1,\"fLine\":\"at com.bluetriangle.android.demo.groupingpoc.tabs.FourthTabFragment.\$r8\$lambda\$o6MLDbfFwXoWc-iaOZGYbmY4fwc(FourthTabFragment.kt:0)\"},{\"i\":2,\"fLine\":\"at com.bluetriangle.android.demo.groupingpoc.tabs.FourthTabFragment$\$ExternalSyntheticLambda0.onClick(D8$\$SyntheticClass:0)\"},{\"i\":3,\"fLine\":\"at android.view.View.performClick(View.java:8220)\"},{\"i\":4,\"fLine\":\"at com.google.android.material.button.MaterialButton.performClick(MaterialButton.java:1202)\"},{\"i\":5,\"fLine\":\"at android.view.View.performClickInternal(View.java:8197)\"},{\"i\":6,\"fLine\":\"at android.view.View.-$\$Nest\$mperformClickInternal(View.java:0)\"},{\"i\":7,\"fLine\":\"at android.view.View\$PerformClick.run(View.java:32040)\"},{\"i\":8,\"fLine\":\"at android.os.Handler.handleCallback(Handler.java:1082)\"},{\"i\":9,\"fLine\":\"at android.os.Handler.dispatchMessageImpl(Handler.java:135)\"},{\"i\":10,\"fLine\":\"at android.os.Handler.dispatchMessage(Handler.java:126)\"},{\"i\":11,\"fLine\":\"at android.os.Looper.loopOnce(Looper.java:295)\"},{\"i\":12,\"fLine\":\"at android.os.Looper.loop(Looper.java:398)\"},{\"i\":13,\"fLine\":\"at android.app.ActivityThread.main(ActivityThread.java:9569)\"},{\"i\":14,\"fLine\":\"at java.lang.reflect.Method.invoke(Native Method)\"},{\"i\":15,\"fLine\":\"at com.android.internal.os.RuntimeInit\$MethodAndArgsCaller.run(RuntimeInit.java:575)\"},{\"i\":16,\"fLine\":\"at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:918)\"}]}]}"
//            )
        }

        view.findViewById<Button>(R.id.anr_button).setOnClickListener {
            Thread.sleep(25000)
        }

        view.findViewById<Button>(R.id.launch_compose).setOnClickListener {
            startActivity(Intent(context, ComposeMainActivity::class.java))
        }

        view.findViewById<Button>(R.id.use_memory).setOnClickListener {
            useMemory()
        }

        view.findViewById<Button>(R.id.release_memory).setOnClickListener {
            clearMemory()
        }

        view.findViewById<Button>(R.id.jank_test).setOnClickListener {
            startActivity(Intent(context, JankTestActivity::class.java))
        }

        view.findViewById<Button>(R.id.scroll_jank_test).setOnClickListener {
            startActivity(Intent(context, ScrollJankTestActivity::class.java))
        }

        view.findViewById<Button>(R.id.hybrid_demo).setOnClickListener {
            startActivity(Intent(context, HybridDemoLayoutActivity::class.java))
        }
    }
}