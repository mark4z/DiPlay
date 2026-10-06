package com.shilapi.xcertplay.generic

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.net.URI
import java.security.MessageDigest

/** Deliberately independent of any vendor preferences or automatic state migration. */
internal data class GenericSettings(
    val wireless: Boolean = false,
    val hotspotMode: WirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
    val ssid: String = "",
    val passphrase: String = "",
    val bluetoothAddress: String = "",
    val mfiTarget: MfiTarget = MfiTarget.LOCAL,
    val usbVendorId: String = "",
    val usbProductId: String = "",
    val i2cPath: String = "/dev/i2c-1",
    val remoteServer: String = "",
    val remoteToken: String = "",
    val microphone: Boolean = true,
    val widthMillimeters: Int = 300,
) {
    fun validationError(): String? {
        if (wireless && hotspotMode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI)) {
            ManualHotspotValidation.validate(ssid, passphrase)?.let { return it }
        }
        if (wireless && bluetoothAddress.isNotBlank() &&
            !bluetoothAddress.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"))) {
            return "Enter a Bluetooth address as six hex pairs, or leave it blank for automatic selection."
        }
        when (mfiTarget) {
            MfiTarget.USB_CH341 -> if (usbId(usbVendorId) == null || usbId(usbProductId) == null) {
                return "Enter the actual CH341 USB vendor and product IDs in hexadecimal."
            }
            MfiTarget.I2C -> if (!i2cPath.matches(Regex("/dev/i2c-[0-9]+"))) {
                return "Enter an accessible Linux I2C device path such as /dev/i2c-1."
            }
            MfiTarget.REMOTE -> {
                val uri = runCatching { URI(remoteServer) }.getOrNull()
                if (uri?.scheme !in listOf("https", "http") || uri?.host.isNullOrBlank() ||
                    uri?.userInfo != null || uri?.query != null || uri?.fragment != null) {
                    return "Enter an HTTP(S) server URL without a username, password, query or fragment."
                }
                if (remoteToken.isNotEmpty() && uri?.scheme != "https") {
                    return "Use HTTPS before entering a remote authentication token."
                }
            }
            MfiTarget.LOCAL -> Unit
        }
        if ('\u0000' in remoteToken) return "The token contains an invalid character."
        if (widthMillimeters !in 40..1000) return "Physical display width must be 40–1000 mm."
        return null
    }

    fun runtime(identity: AirPlayIdentity): CarPlayRuntimeConfig {
        require(validationError() == null) { validationError().orEmpty() }
        val deviceId = deviceId(identity)
        return CarPlayRuntimeConfig(
            mfiTarget = mfiTarget,
            ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
                listOf(UsbDeviceId(requireNotNull(usbId(usbVendorId)), requireNotNull(usbId(usbProductId))))
            } else emptyList(),
            linuxI2cPath = i2cPath.takeIf { mfiTarget == MfiTarget.I2C },
            remoteMfiServer = remoteServer.takeIf { mfiTarget == MfiTarget.REMOTE },
            remoteMfiToken = remoteToken.takeIf { mfiTarget == MfiTarget.REMOTE && it.isNotEmpty() },
            identification = Iap2IdentificationConfig(
                name = "DiPlay Generic", modelIdentifier = "Generic Android", manufacturer = "DiPlay",
                serialNumber = "DIPLAY-GENERIC-" + deviceId.replace(":", ""),
                firmwareVersion = "0.1.0", hardwareVersion = "1.0", carPlayUsbInterfaceNumber = 3,
                locationInformationEnabled = false, vehicleStatusEnabled = false, vehicleSpeedEnabled = false,
            ),
            hostMac = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            label = "DiPlay Generic", hostName = "diplay-generic-" + deviceId.replace(":", "").lowercase(),
            transport = if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
            wirelessHotspotMode = hotspotMode,
            manualHotspotSsid = ssid,
            manualHotspotPassphrase = passphrase,
            manualHotspotSecurity = ManualHotspotValidation.securityFor(passphrase),
            existingWifiSsid = ssid,
            existingWifiPassphrase = passphrase,
            wirelessBluetoothDeviceAddress = bluetoothAddress.takeIf { it.isNotBlank() },
            locationReportingEnabled = false,
            launchSystemHomeOnHostUiRequest = false,
        )
    }

    fun airPlay(identity: AirPlayIdentity, width: Int, height: Int, microphoneGranted: Boolean, knobPrimary: Boolean = false): AirPlayConfig {
        val size = canvasSize(width, height)
        return AirPlayConfig(
            deviceName = "DiPlay Generic", deviceId = deviceId(identity), btMac = deviceId(identity),
            sourceVersion = "0.1.0",
            main = AirPlayDisplayConfig(
                widthPixels = size.first, heightPixels = size.second,
                widthPhysicalMm = widthMillimeters,
                heightPhysicalMm = (widthMillimeters.toDouble() * size.second / size.first).toInt().coerceAtLeast(1),
                fps = 30,
                primaryInputDevice = if (knobPrimary) 3 else 1,
            ),
            manufacturer = "DiPlay", model = "Generic Android", oemLabel = "DiPlay",
            microphone = microphone && microphoneGranted,
            // Unknown gear is never treated as parked. This does not disable the core screen stream.
            videoInCar = false, cluster = null, hevc = false,
        )
    }

    companion object {
        fun usbId(value: String): Int? = value.trim().removePrefix("0x").removePrefix("0X")
            .toIntOrNull(16)?.takeIf { it in 1..0xffff }

        fun deviceId(identity: AirPlayIdentity): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).copyOf(6)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }

        /** Bound decoder load, preserve the window's aspect, and advertise even H.264 dimensions. */
        fun canvasSize(width: Int, height: Int): Pair<Int, Int> {
            require(width > 0 && height > 0)
            val scale = minOf(1.0, 1920.0 / width, 1080.0 / height)
            fun even(value: Int) = ((value * scale).toInt() / 2 * 2).coerceAtLeast(2)
            return even(width) to even(height)
        }
    }
}

/** Hardware/UI capabilities, never a brand or model allowlist. */
internal object GenericCapabilities {
    fun knobPrimary(context: Context): Boolean {
        val manager = context.packageManager
        val configuration = context.resources.configuration
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
            configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
    }
}

internal object GenericPermissions {
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun required(settings: GenericSettings, sdk: Int): List<String> = buildList {
        // Direct TCP/UDP and Bonjour are gated on Android 17 for apps targeting SDK 37.
        if (sdk >= 37) add(LOCAL_NETWORK)
        if (settings.wireless) {
            if (sdk >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (settings.hotspotMode != WirelessHotspotMode.EXISTING_WIFI) {
                if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                else {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
    }
}

/** A transient failure cannot cause an unbounded reconnect/permission loop. */
internal class GenericReconnectBudget {
    private var failures = 0
    fun reset() { failures = 0 }
    fun nextDelayMillis(): Long? {
        if (failures >= 5) return null
        return (1000L shl failures++).coerceAtMost(16000L)
    }
}
