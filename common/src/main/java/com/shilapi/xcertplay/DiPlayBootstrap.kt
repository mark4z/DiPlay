package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiIdentityStore
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.security.MessageDigest

/** Loads an imported identity, or optional bundled assets. There is no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        if (mfiTarget != MfiTarget.LOCAL) return
        if (ready) return
        identityStore(context).ensureInstalled { name ->
            context.assets.open("offline-mfi/$name")
        }
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    @Synchronized fun beginIdentityImport(context: Context): LocalMfiIdentityStore.ImportSession =
        identityStore(context).beginImport()

    @Synchronized fun hasImportedIdentity(context: Context): Boolean =
        identityStore(context).hasImportedIdentity()

    /**
     * The activity rechecks that it is disconnected immediately before calling this.
     * Throws only if committing failed; false means the pair was committed but reload failed.
     */
    @Synchronized fun completeIdentityImport(
        context: Context,
        session: LocalMfiIdentityStore.ImportSession,
        mfiTarget: MfiTarget,
    ): Boolean {
        session.commit()
        return runCatching { reinitialize(context, mfiTarget) }.isSuccess
    }

    @Synchronized fun reinitialize(context: Context, mfiTarget: MfiTarget) {
        ready = false
        ensure(context, mfiTarget)
    }

    private fun identityStore(context: Context) = LocalMfiIdentityStore(context.noBackupFilesDir)

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}
