package com.bluetriangle.android.demo.kotlin

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Tracker.Companion.instance
import com.bluetriangle.analytics.okhttp.bttTrack
import com.bluetriangle.android.demo.DemoApplication.Companion.checkLaunchTest
import com.bluetriangle.android.demo.R
import com.bluetriangle.android.demo.databinding.ActivityTestListBinding
import com.bluetriangle.android.demo.kotlin.screenTracking.ScreenTrackingActivity
import com.bluetriangle.android.demo.tests.ANRTest
import com.bluetriangle.android.demo.tests.ANRTestScenario
import com.bluetriangle.android.demo.tests.LaunchTestScenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import com.bluetriangle.android.demo.getViewModel

@Suppress("UNUSED_PARAMETER")
class KotlinTestListActivity : AppCompatActivity() {
    private var timer: Timer? = null

    private var okHttpClient: OkHttpClient? = null

    private lateinit var binding: ActivityTestListBinding
    private lateinit var viewModel: TestListViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_test_list)
        setTitle(R.string.main_title)

        viewModel = getViewModel()
        binding.viewModel = viewModel

        updateButtonState()
        addButtonClickListeners()

        okHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(0L, TimeUnit.SECONDS)
                .callTimeout(0L, TimeUnit.SECONDS)
                .bttTrack()
                .build()
    }

    private fun addButtonClickListeners() {
        binding.buttonStart.setOnClickListener(this::startButtonClicked)
        binding.buttonInteractive.setOnClickListener(this::interactiveButtonClicked)
        binding.buttonStop.setOnClickListener(this::stopButtonClicked)
        binding.buttonNext.setOnClickListener(this::nextButtonClicked)
        binding.buttonBackground.setOnClickListener(this::backgroundButtonClicked)
        binding.buttonCrash.setOnClickListener(this::crashButtonClicked)
        binding.buttonTrackCatchException.setOnClickListener(this::trackCatchExceptionButtonClicked)
        binding.buttonNetwork.setOnClickListener(this::captureNetworkRequests)
        binding.buttonScreenTrack.setOnClickListener(this::screenTrackButtonClicked)
        binding.buttonAnr.setOnClickListener {
            launchAnrActivity(ANRTestScenario.Unknown, ANRTest.Unknown)
        }
        binding.cpuTest.setOnClickListener {
            startActivity(Intent(this, CPUTestActivity::class.java))
        }

        binding.memoryTest.setOnClickListener {
            startActivity(Intent(this, MemoryTestActivity::class.java))
        }

        binding.jankTest.setOnClickListener {
            startActivity(Intent(this, JankTestActivity::class.java))
        }

        binding.scrollJankTest.setOnClickListener {
            startActivity(Intent(this, ScrollJankTestActivity::class.java))
        }

        binding.buttonLaunchGallery.setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
            }, "Pick Image"))
        }

        binding.buttonLongNetwork.setOnClickListener {
            val timer = Timer("Long Network Request", "Android Traffic").start()
            val longRequest: Request =
                Request.Builder().url("https://hub.dummyapis.com/delay?seconds=15").build()
            okHttpClient!!.newCall(longRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.d(
                        TAG,
                        "onFailure: ${e::class.java.simpleName}(\"${e.message}\")  " + call.request()
                    )
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call, response: Response) {
                    Log.d(TAG, "onResponse: " + call.request())
                    timer.end().submit()
                }
            })

        }
        binding.btnAnrTestRun.setOnClickListener {
            launchAnrActivity(viewModel.anrTestScenario.value!!, viewModel.anrTest.value!!)
        }
    }

    private fun updateButtonState() {
        binding.buttonStart.isEnabled = timer == null || !timer!!.isRunning() || timer!!.hasEnded()
        binding.buttonInteractive.isEnabled =
            timer != null && timer!!.isRunning() && !timer!!.isInteractive() && !timer!!.hasEnded()
        binding.buttonStop.isEnabled = timer != null && timer!!.isRunning() && !timer!!.hasEnded()
    }

    private fun startButtonClicked(view: View) {
        timer = Timer(KotlinTestListActivity::class.java.simpleName, "Ä Traffic Šegment").start()
        updateButtonState()
    }

    private fun interactiveButtonClicked(view: View) {
        if (timer!!.isRunning()) {
            timer!!.interactive()
        }
        updateButtonState()
    }

    private fun stopButtonClicked(view: View) {
        if (timer!!.isRunning()) {
            timer!!.end().submit()
        }
        updateButtonState()
    }

    private fun nextButtonClicked(view: View) {
        val timer = Timer("Next Page", "Android Traffic").start()
        val intent = Intent(this, NextActivity::class.java)
        intent.putExtra(Timer.EXTRA_TIMER, timer)
        startActivity(intent)
    }

    private fun screenTrackButtonClicked(view: View) {
        val intent = Intent(this, ScreenTrackingActivity::class.java)
        startActivity(intent)
    }

    private fun backgroundButtonClicked(view: View) {
        val backgroundTimer = Timer("Background Timer", "background traffic").start()

        lifecycleScope.launch {
            try {
                delay(500)
                backgroundTimer.interactive()
                delay(2000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            backgroundTimer.end().submit()
        }
    }

    private fun trackCatchExceptionButtonClicked(view: View) {
        try {
            instance!!.raiseTestException()
        } catch (e: Throwable) {
            instance!!.trackException("A test exception caught!", e)
        }
    }

    private fun crashButtonClicked(view: View) {
        instance!!.raiseTestException()
        //NativeWrapper().testCrash()
    }

    private fun captureNetworkRequests(view: View) {
        val timer = Timer("Test Network Capture", "Android Traffic").start()
        val imageRequest: Request =
            Request.Builder().url("https://www.httpbin.org/image/jpeg").build()
        okHttpClient!!.newCall(imageRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "onFailure: " + call.request())
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "onResponse: " + call.request())
            }
        })
        val jsonRequest: Request = Request.Builder().url("https://www.httpbin.org/json").build()
        okHttpClient!!.newCall(jsonRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "onFailure: " + call.request())
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "onResponse: " + call.request())
                timer.end().submit()
                val body: RequestBody = FormBody.Builder().add("test", "value").build()
                val postRequest: Request =
                    Request.Builder().url("https://httpbin.org/post").method("POST", body).build()
                okHttpClient!!.newCall(postRequest).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.d(TAG, "onFailure: " + call.request())
                    }

                    @Throws(IOException::class)
                    override fun onResponse(call: Call, response: Response) {
                        Log.d(TAG, "onResponse: " + call.request())
                        Timer("Test Network Capture 2", "Android Traffic").start().end().submit()
                    }
                })
            }
        })
    }

    private fun launchAnrActivity(anrTestScenario: ANRTestScenario, anrTest: ANRTest) {
        val intent = Intent(this, ANRTestActivity::class.java)
        intent.putExtra(ANRTestActivity.TestScenario, anrTestScenario)
        intent.putExtra(ANRTestActivity.Test, anrTest)
        startActivity(intent)
        //NativeWrapper().testANR()
    }

    override fun onStart() {
        super.onStart()
        checkLaunchTest(LaunchTestScenario.OnActivityStart)
    }

    override fun onResume() {
        super.onResume()
        checkLaunchTest(LaunchTestScenario.OnActivityResume)
    }

    companion object {
        private const val TAG = "BlueTriangle"
    }
}