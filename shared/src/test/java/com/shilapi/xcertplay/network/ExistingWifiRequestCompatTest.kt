package com.shilapi.xcertplay.network

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Same LAN must work on our minimum Android 9 as well as the API-30 builder path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29, 30], manifest = Config.NONE)
class ExistingWifiRequestCompatTest {
    @Test
    fun callbackRequestDoesNotRequireInternetOrDefaultTrustCapabilities() {
        val manager = ExistingWifiManager(RuntimeEnvironment.getApplication(), "CarPlay LAN", "")
        try {
            val method = ExistingWifiManager::class.java
                .getDeclaredMethod("requestWithoutDefaultCapabilities")
                .apply { isAccessible = true }
            val builder = method.invoke(manager) as NetworkRequest.Builder
            val request = builder
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
            assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED))
        } finally {
            manager.close()
        }
    }
}
