package com.bluetriangle.android.demo

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.bluetriangle.analytics.BlueTriangleConfiguration
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.Tracker.Companion.init
import com.bluetriangle.android.demo.tests.ANRTest
import com.bluetriangle.android.demo.tests.ANRTestFactory
import com.bluetriangle.android.demo.tests.ANRTestScenario
import com.bluetriangle.android.demo.tests.HeavyLoopTest
import com.bluetriangle.android.demo.tests.LaunchTestScenario

class DemoApplication : Application() {
    private var tracker: Tracker? = null

    companion object {
        lateinit var sharedPreferencesMgr: SharedPreferencesMgr
        lateinit var tinyDB: TinyDB
        const val DEFAULT_SITE_ID = "sdkdevtest500z"//"sdkdemo26621z"
        const val TAG_URL = "TAG_URL"
        const val DEFAULT_TAG_URL = "$DEFAULT_SITE_ID.btttag.com/btt.js"

        private var demoWebsiteUrl = ""
        val DEMO_WEBSITE_URL
            get() = demoWebsiteUrl

        fun checkLaunchTest(scenario: LaunchTestScenario) {
            val launchScenarioId = sharedPreferencesMgr.getInt("LaunchScenario", -1)
            Log.d("CheckAndLaunchTest", "LaunchScenarioId: $launchScenarioId")
            if(launchScenarioId == -1) return
            val launchScenario = LaunchTestScenario.values()[launchScenarioId]

            if(launchScenario == scenario) {
                sharedPreferencesMgr.remove("LaunchScenario")
                HeavyLoopTest(3L).run()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        tinyDB = TinyDB(applicationContext)

        sharedPreferencesMgr = SharedPreferencesMgr(applicationContext)

        initTracker(DEFAULT_SITE_ID)
        // d.btttag.com => 107.22.227.162
        //"http://107.22.227.162/btt.gif"
        //https://d.btttag.com/analytics.rcv
        //sdkdemo26621z
        //bluetriangledemo500z

        demoWebsiteUrl = "file://${filesDir.absolutePath}/index.html"

        if(!hasTagUrl()) {
            setTagUrl(DEFAULT_TAG_URL)
        }
        checkANRTestOnAppCreate()
        checkLaunchTest(LaunchTestScenario.OnApplicationCreate)
    }

    fun initTracker(siteId: String?) {
        if (siteId.isNullOrBlank()) return

        val configuration = BlueTriangleConfiguration()
        configuration.isScreenTrackingEnabled = true
        configuration.isTrackCrashesEnabled = true
        configuration.isTrackAnrEnabled = true
        configuration.siteId = siteId
        configuration.isDebug = true
        configuration.debugLevel = Log.VERBOSE
        configuration.networkSampleRate = 1.0
        configuration.isPerformanceMonitorEnabled = true
        configuration.performanceMonitorIntervalMs = 500
        configuration.isLaunchTimeEnabled = true
        configuration.isTrackNetworkStateEnabled = true
        configuration.isMemoryWarningEnabled = true
        tracker = init(this, configuration)

        tracker?.setSessionTrafficSegmentName("Demo Traffic Segment")
    }

    private fun checkANRTestOnAppCreate() {
        val anrTestScenarioId = sharedPreferencesMgr.getInt("ANRTestScenario")
        val anrTestId = sharedPreferencesMgr.getInt("ANRTest")
        sharedPreferencesMgr.remove("ANRTestScenario")
        sharedPreferencesMgr.remove("ANRTest")

        Log.e("ANRTestScenario", anrTestScenarioId.toString())
        Log.e("ANRTest", anrTestId.toString())

        val anrTestScenario = ANRTestScenario.values()[anrTestScenarioId]
        val anrTest = ANRTest.values()[anrTestId]
        if (anrTestScenario == ANRTestScenario.OnApplicationCreate && anrTest != ANRTest.Unknown) {
            ANRTestFactory.getANRTest(anrTest).run()
        }
    }


    fun hasTagUrl(): Boolean {
        return tinyDB.contains(TAG_URL)
    }

    fun getTagUrl(): String {
        return tinyDB.getString(TAG_URL, DEFAULT_TAG_URL) ?: DEFAULT_TAG_URL
    }

    fun setTagUrl(url: String) {
        tinyDB.setString(TAG_URL, url)
        generateDemoWebsiteFromTemplate()
    }
}