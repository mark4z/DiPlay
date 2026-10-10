package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessPermissionsTest {
    @Test fun everyModeAddsOnlyLocalNetworkAtTheAndroid17Boundary() {
        for (mode in WirelessHotspotMode.values()) {
            val before = WirelessPermissions.required(mode, 36)
            val current = WirelessPermissions.required(mode, 37)
            assertEquals(before + Manifest.permission.ACCESS_LOCAL_NETWORK, current)
            assertEquals(current, WirelessPermissions.required(mode, 38))
            assertEquals(current.size, current.distinct().size)
            assertFalse(before.contains(Manifest.permission.ACCESS_LOCAL_NETWORK))
            assertFalse(current.contains(Manifest.permission.RECORD_AUDIO))
        }
    }

    @Test fun olderVersionsKeepTheirOriginalLocationAndNearbyScopes() {
        for (mode in WirelessHotspotMode.values()) {
            for (sdk in listOf(28, 29, 30, 31, 32, 33, 34, 35, 36)) {
                val expected = when {
                    mode == WirelessHotspotMode.EXISTING_WIFI ->
                        if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
                    sdk >= 33 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
                    sdk >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                    else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                }
                assertEquals("mode=$mode sdk=$sdk", expected, WirelessPermissions.required(mode, sdk))
            }
        }
    }

    @Test fun sameLanDoesNotGainLocationScanOrMicrophonePermissions() {
        val requested = WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 37)
        assertTrue(requested.contains(Manifest.permission.ACCESS_LOCAL_NETWORK))
        assertFalse(requested.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(requested.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertFalse(requested.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        assertFalse(requested.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test fun hotspotModesRequestLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 37),
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 29),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 31),
        )
    }

    @Test fun existingWifiRequestsLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 37),
        )
        assertEquals(emptyList<String>(), WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 29))
    }
}
