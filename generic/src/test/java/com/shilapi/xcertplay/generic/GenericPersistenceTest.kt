package com.shilapi.xcertplay.generic

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GenericPersistenceTest {
    @Test fun oldHostSettingsAreNeverImported() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putBoolean("wireless_enabled", true).putBoolean("cluster_map_enabled", true)
            .putString("remote_mfi_server", "https://unused.invalid").commit()
        val actual = GenericPersistence(context).loadSettings()
        assertEquals(GenericSettings(), actual)
    }

    @Test fun ownSettingsRoundTripWithIndependentIdentityAndPairings() {
        val context = RuntimeEnvironment.getApplication()
        val store = GenericPersistence(context)
        val selected = GenericSettings(wireless = true, ssid = "Synthetic test network", passphrase = "synthetic-pass")
        store.saveSettings(selected)
        assertEquals(selected, GenericPersistence(context).loadSettings())
        val first = store.identity()
        val second = GenericPersistence(context).identity()
        assertEquals(first.pairingId, second.pairingId)
        assertArrayEquals(first.privateKey, second.privateKey)
        assertArrayEquals(first.publicKey, second.publicKey)
        val key = ByteArray(32) { (it + 7).toByte() }
        store.pairings().save("synthetic-peer", key)
        assertArrayEquals(key, GenericPersistence(context).pairings().get("synthetic-peer"))
    }

    @Test fun corruptStoredKeysAreReplacedWithoutCrashing() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("generic_pairings_v1", Context.MODE_PRIVATE).edit()
            .putString("private", "not-base64!").putString("public", "bad")
            .putString("pairing_id", "broken").commit()
        val identity = GenericPersistence(context).identity()
        assertEquals(32, identity.privateKey.size)
        assertNotEquals("broken", identity.pairingId)
    }

    @Test fun sourceBuildDoesNotSilentlySupplyLocalAuthentication() {
        val context = RuntimeEnvironment.getApplication()
        assertThrows(Exception::class.java) { GenericAuthentication.ensureLocal(context) }
        assertFalse(context.noBackupFilesDir.resolve("offline-mfi").exists())
        assertFalse(context.noBackupFilesDir.resolve("generic-auth-staging").exists())
    }
}
