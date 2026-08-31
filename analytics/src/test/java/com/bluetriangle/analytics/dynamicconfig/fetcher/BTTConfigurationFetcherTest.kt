package com.bluetriangle.analytics.dynamicconfig.fetcher

import com.bluetriangle.analytics.Logger
import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsConfig
import com.bluetriangle.analytics.checkout.config.CheckoutConfig
import com.bluetriangle.analytics.dynamicconfig.model.BTTRemoteConfiguration
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import javax.net.ssl.HttpsURLConnection


@RunWith(RobolectricTestRunner::class)
class BTTConfigurationFetcherTest {

    companion object {
        private const val HOST: String = "https://localhost:3000/"
        private val MOCK_RESPONSE: String = """{
                                                 "enableAllTracking": %b,
                                                 "networkSampleRateSDK": %d,
                                                 "ignoreScreens": [%s, %s],
                                                 "enableGrouping": %b,
                                                 "enableScreenTracking": %b,
                                                 "groupedViewSampleRate": %d,
                                                 "groupingIdleTime": %d,
                                                 "configKey": "sdkdemo26621z_SDKConfigJSON",
                                                 "configVersion": "2.0.0",
                                                 "configGenerated": "Sat, 01 Nov 2025 16:09:16 GMT"
                                               }
                                               """

    }
    private lateinit var fetcher: IBTTConfigurationFetcher

    private lateinit var mockWebServer: MockWebServer

    @Mock
    private lateinit var mockLogger: Logger

    @Before
    @Throws(Exception::class)
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // Generate a self-signed certificate using OkHttp's HeldCertificate
        val heldCertificate: HeldCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .build()

        // Create SSL configuration for MockWebServer
        val serverCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()

        // Start the MockWebServer with HTTPS
        mockWebServer = MockWebServer()
        mockWebServer.useHttps(
            serverCertificates.sslSocketFactory(),
            false
        ) // Set MockWebServer to use HTTPS
        mockWebServer.start()

        // Set up client-side certificates to trust MockWebServer's certificate
        val clientCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCertificate.certificate)
            .build()

        // Trust the mock server's certificate by configuring HttpsURLConnection
        HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory())

        // Build the ApiClient with the mock server's HTTPS URL
        val mockUrl = mockWebServer.url("/config.js?siteID=sdkdemo26621z").toString()

        fetcher = BTTConfigurationFetcher(mockLogger, mockUrl)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test(timeout = 5000)
    @Throws
    fun `Should return success with correct configuration object`() {
        runBlocking {
            mockWebServer.enqueue(MockResponse().setBody(String.format(Locale.ENGLISH, MOCK_RESPONSE, true, 70, "HomeActivity", "ProductsFragment", false, true, 30, 5)))

            val config = BTTRemoteConfiguration(
                networkSampleRate = 0.7,
                ignoreScreens = listOf("HomeActivity", "ProductsFragment"),
                true,
                false,
                true,
                2,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                CheckoutConfig.DEFAULT,
                BreadcrumbsConfig.DEFAULT,
                "",
                true,
                true,
                10.0,
                true,
                true
            )
            val result = fetcher.fetch()

            assertTrue("Result should be success but is : ${(result as? BTTConfigFetchResult.Failure)?.error}", result is BTTConfigFetchResult.Success)

            assertTrue(
                "Didn't receive correct config actual: ${(result as BTTConfigFetchResult.Success).config}, expected: $config",
                result.config == config
            )
        }
    }

}
