package com.shilapi.xcertplay.generic

import android.Manifest
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test

class GenericSettingsTest {
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32) { it.toByte() }, "synthetic-test-identity")

    @Test fun freshDefaultsAreWiredLocalAndHaveNoVehicleFeatures() {
        val settings = GenericSettings()
        assertNull(settings.validationError())
        val runtime = settings.runtime(identity)
        assertEquals(CarPlayTransport.WIRED, runtime.transport)
        assertEquals(MfiTarget.LOCAL, runtime.mfiTarget)
        assertFalse(runtime.allowAdbWifiScanPause)
        assertFalse(runtime.launchSystemHomeOnHostUiRequest)
        assertFalse(runtime.locationReportingEnabled)
        assertFalse(runtime.identification.vehicleStatusEnabled)
        assertFalse(runtime.identification.vehicleSpeedEnabled)
        assertFalse(runtime.identification.locationInformationEnabled)
        assertTrue(runtime.ch341Devices.isEmpty())
    }

    @Test fun noSettingsCanEnableParkedVideoOrCluster() {
        for (mode in WirelessHotspotMode.entries) {
            val config = GenericSettings(wireless = true, hotspotMode = mode).airPlay(identity, 1280, 720, true)
            assertFalse(config.videoInCar)
            assertNull(config.cluster)
            assertEquals(1280, config.main.widthPixels)
            assertEquals(720, config.main.heightPixels)
            assertTrue(config.microphone)
        }
    }

    @Test fun microphoneIsOffWhenEitherSettingOrPermissionIsOff() {
        assertFalse(GenericSettings().airPlay(identity, 1280, 720, false).microphone)
        assertFalse(GenericSettings(microphone = false).airPlay(identity, 1280, 720, true).microphone)
    }

    @Test fun nonTouchAndTvHostsAdvertiseNativeKnobFocus() {
        assertEquals(3, GenericSettings().airPlay(identity, 1280, 720, true, knobPrimary = true).main.primaryInputDevice)
        assertEquals(1, GenericSettings().airPlay(identity, 1280, 720, true, knobPrimary = false).main.primaryInputDevice)
    }

    @Test fun allWirelessBackendsReachRuntimeWithoutVendorSettings() {
        for (mode in WirelessHotspotMode.entries) {
            val config = GenericSettings(wireless = true, hotspotMode = mode, ssid = "Test network", passphrase = "test-only-pass")
                .runtime(identity)
            assertEquals(CarPlayTransport.WIRELESS, config.transport)
            assertEquals(mode, config.wirelessHotspotMode)
            assertFalse(config.allowAdbWifiScanPause)
        }
    }

    @Test fun manualAndSameLanRequireUsableCredentialsButWiredIgnoresThem() {
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI)) {
            assertNotNull(GenericSettings(wireless = true, hotspotMode = mode).validationError())
            assertNotNull(GenericSettings(wireless = true, hotspotMode = mode, ssid = "Test", passphrase = "short").validationError())
            assertNull(GenericSettings(wireless = true, hotspotMode = mode, ssid = "Test").validationError())
            assertNull(GenericSettings(wireless = false, hotspotMode = mode).validationError())
        }
    }

    @Test fun remoteTokenCannotUseCleartextHttp() {
        assertNotNull(GenericSettings(mfiTarget = MfiTarget.REMOTE, remoteServer = "http://test.invalid", remoteToken = "synthetic").validationError())
        assertNull(GenericSettings(mfiTarget = MfiTarget.REMOTE, remoteServer = "https://test.invalid", remoteToken = "synthetic").validationError())
        assertNotNull(GenericSettings(mfiTarget = MfiTarget.REMOTE, remoteServer = "https://name:password@test.invalid").validationError())
        assertNotNull(GenericSettings(mfiTarget = MfiTarget.REMOTE, remoteServer = "https://test.invalid?token=synthetic").validationError())
    }

    @Test fun hardwareIdsMustBeExplicitAndValid() {
        assertNotNull(GenericSettings(mfiTarget = MfiTarget.USB_CH341).validationError())
        assertNull(GenericSettings.usbId("10000"))
        assertNull(GenericSettings.usbId("-1"))
        assertEquals(0x1a86, GenericSettings.usbId("0x1a86"))
        val runtime = GenericSettings(mfiTarget = MfiTarget.USB_CH341, usbVendorId = "1a86", usbProductId = "5512").runtime(identity)
        assertEquals(0x5512, runtime.ch341Devices.single().productId)
        assertNull(runtime.ch341MfiResetGpio)
    }

    @Test fun optionalBluetoothSelectionMustBeAnAddress() {
        assertNotNull(GenericSettings(wireless = true, bluetoothAddress = "iPhone").validationError())
        assertNull(GenericSettings(wireless = true, bluetoothAddress = "00:11:22:33:44:55").validationError())
    }

    @Test fun canvasIsEvenBoundedAndAspectPreserving() {
        assertEquals(1920 to 1080, GenericSettings.canvasSize(3840, 2160))
        assertEquals(606 to 1080, GenericSettings.canvasSize(1080, 1920))
        val result = GenericSettings.canvasSize(1279, 719)
        assertEquals(0, result.first % 2)
        assertEquals(0, result.second % 2)
    }

    @Test fun physicalWidthSupportsPhonesAndLargeGenericDisplays() {
        assertNull(GenericSettings(widthMillimeters = 70).validationError())
        assertNull(GenericSettings(widthMillimeters = 1000).validationError())
        assertNotNull(GenericSettings(widthMillimeters = 0).validationError())
        assertEquals(70, GenericSettings(widthMillimeters = 70).airPlay(identity, 1080, 1920, false).main.widthPhysicalMm)
    }

    @Test fun derivedAddressIsStableLocallyAdministeredAndUnicast() {
        val id = GenericSettings.deviceId(identity)
        assertEquals(id, GenericSettings.deviceId(identity))
        assertEquals(2, id.substringBefore(':').toInt(16) and 3)
    }

    @Test fun runtimePermissionsFollowSdkAndNetworkMode() {
        assertTrue(GenericPermissions.required(GenericSettings(), 28).isEmpty())
        assertEquals(listOf(GenericPermissions.LOCAL_NETWORK), GenericPermissions.required(GenericSettings(), 37))
        val older = GenericPermissions.required(GenericSettings(wireless = true), 31)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in older)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in older)
        val modern = GenericPermissions.required(GenericSettings(wireless = true), 33)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in modern)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in modern)
        val lan = GenericPermissions.required(GenericSettings(wireless = true, hotspotMode = WirelessHotspotMode.EXISTING_WIFI), 37)
        assertEquals(setOf(Manifest.permission.BLUETOOTH_CONNECT, GenericPermissions.LOCAL_NETWORK), lan.toSet())
    }

    @Test fun reconnectBudgetIsBoundedAndCanBeResetAfterStableVideo() {
        val budget = GenericReconnectBudget()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), List(5) { budget.nextDelayMillis() })
        assertNull(budget.nextDelayMillis())
        assertNull(budget.nextDelayMillis())
        budget.reset()
        assertEquals(1000L, budget.nextDelayMillis())
    }
}
