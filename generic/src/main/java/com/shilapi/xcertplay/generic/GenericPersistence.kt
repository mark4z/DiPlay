package com.shilapi.xcertplay.generic

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.LockdownPairRecord

/** Only the separate generic application's private storage; backups/transfers are excluded. */
internal class GenericPersistence(context: Context) {
    private val prefs = context.getSharedPreferences("generic_connection_v1", Context.MODE_PRIVATE)
    private val pairingPrefs = context.getSharedPreferences("generic_pairings_v1", Context.MODE_PRIVATE)
    private val lockdown = context.getSharedPreferences("generic_lockdown_v1", Context.MODE_PRIVATE)

    fun loadSettings(): GenericSettings = GenericSettings(
        wireless = prefs.getBoolean("wireless", false),
        hotspotMode = enumValue(prefs.getString("hotspot", null), WirelessHotspotMode.WIFI_P2P),
        ssid = prefs.text("ssid"), passphrase = prefs.text("passphrase"),
        bluetoothAddress = prefs.text("bluetooth"),
        mfiTarget = enumValue(prefs.getString("mfi", null), MfiTarget.LOCAL),
        usbVendorId = prefs.text("usb_vendor"), usbProductId = prefs.text("usb_product"),
        i2cPath = prefs.getString("i2c_path", "/dev/i2c-1").orEmpty(),
        remoteServer = prefs.text("remote_server"), remoteToken = prefs.text("remote_token"),
        microphone = prefs.getBoolean("microphone", true),
        widthMillimeters = prefs.getInt("width_mm", 300).coerceIn(40, 1000),
    )

    fun saveSettings(settings: GenericSettings) {
        prefs.edit().putBoolean("wireless", settings.wireless)
            .putString("hotspot", settings.hotspotMode.name)
            .putString("ssid", settings.ssid).putString("passphrase", settings.passphrase)
            .putString("bluetooth", settings.bluetoothAddress).putString("mfi", settings.mfiTarget.name)
            .putString("usb_vendor", settings.usbVendorId).putString("usb_product", settings.usbProductId)
            .putString("i2c_path", settings.i2cPath).putString("remote_server", settings.remoteServer)
            .putString("remote_token", settings.remoteToken).putBoolean("microphone", settings.microphone)
            .putInt("width_mm", settings.widthMillimeters).apply()
    }

    @Synchronized fun identity(): AirPlayIdentity {
        val stored = runCatching {
            val privateKey = pairingPrefs.bytes("private") ?: return@runCatching null
            val publicKey = pairingPrefs.bytes("public") ?: return@runCatching null
            val id = pairingPrefs.getString("pairing_id", null)?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            if (privateKey.size != 32 || publicKey.size != 32) return@runCatching null
            AirPlayIdentity(privateKey, publicKey, id)
        }.getOrNull()
        if (stored != null) return stored
        return AirPlayIdentity.generate().also { identity ->
            // A damaged accessory identity also invalidates its paired-controller keys.
            check(pairingPrefs.edit().clear().putBytes("private", identity.privateKey)
                .putBytes("public", identity.publicKey).putString("pairing_id", identity.pairingId).commit()) {
                "Could not persist accessory identity"
            }
        }
    }

    @Synchronized fun pairings(): PairingStore = PairingStore(::savePairing).also { store ->
        for (id in pairingPrefs.getStringSet("paired_ids", emptySet()).orEmpty()) {
            runCatching { pairingPrefs.bytes("peer.$id") }.getOrNull()?.takeIf { it.size == 32 }
                ?.let { store.save(id, it) }
        }
    }

    @Synchronized private fun savePairing(id: String, publicKey: ByteArray) {
        val ids = pairingPrefs.getStringSet("paired_ids", emptySet()).orEmpty().toMutableSet().apply { add(id) }
        pairingPrefs.edit().putStringSet("paired_ids", ids).putBytes("peer.$id", publicKey).apply()
    }

    fun loadLockdown(): LockdownPairRecord? = runCatching {
        if (!lockdown.contains("host_id")) return@runCatching null
        LockdownPairRecord.restore(
            hostId = lockdown.text("host_id"), systemBuid = lockdown.text("system_buid"),
            wifiMacAddress = lockdown.text("wifi_mac"),
            devicePublicKeyPem = requireNotNull(lockdown.bytes("device_public")),
            deviceCertificatePem = requireNotNull(lockdown.bytes("device_cert")),
            hostPrivateKeyPem = requireNotNull(lockdown.bytes("host_private")),
            hostCertificatePem = requireNotNull(lockdown.bytes("host_cert")),
            rootPrivateKeyPem = requireNotNull(lockdown.bytes("root_private")),
            rootCertificatePem = requireNotNull(lockdown.bytes("root_cert")),
        )
    }.getOrNull()

    fun saveLockdown(record: LockdownPairRecord) {
        lockdown.edit().putString("host_id", record.hostId).putString("system_buid", record.systemBuid)
            .putString("wifi_mac", record.wifiMacAddress).putBytes("device_public", record.devicePublicKeyPem)
            .putBytes("device_cert", record.deviceCertificatePem).putBytes("host_private", record.hostPrivateKeyPem)
            .putBytes("host_cert", record.hostCertificatePem).putBytes("root_private", record.rootPrivateKeyPem)
            .putBytes("root_cert", record.rootCertificatePem).apply()
    }

    fun clearLockdown() { lockdown.edit().clear().apply() }

    private fun SharedPreferences.text(key: String): String = getString(key, "").orEmpty()
    private fun SharedPreferences.bytes(key: String): ByteArray? = getString(key, null)?.let {
        Base64.decode(it, Base64.NO_WRAP)
    }
    private fun SharedPreferences.Editor.putBytes(key: String, bytes: ByteArray): SharedPreferences.Editor =
        putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP))
    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default
}
