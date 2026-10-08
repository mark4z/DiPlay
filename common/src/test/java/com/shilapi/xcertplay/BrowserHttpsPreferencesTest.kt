package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserHttpsPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val defaults = BrowserHttpsPreferences.Settings()
    private fun file() = File(temporary.newFolder(), "settings.bin")

    @Test fun defaultsOffAndBothOptionsPersistIndependently() {
        val file = file()
        assertEquals(defaults, BrowserHttpsPreferences(file).load())
        for (start in listOf(false, true)) for (allow in listOf(false, true)) {
            val settings = BrowserHttpsPreferences.Settings(start, allow)
            BrowserHttpsPreferences(file).save(settings)
            assertEquals(settings, BrowserHttpsPreferences(file).load())
        }
        assertEquals(9L, file.length())
    }

    @Test fun truncatedOversizedUnknownVersionAndUnknownFlagsFailClosed() {
        val file = file()
        val preferences = BrowserHttpsPreferences(file)
        preferences.save(BrowserHttpsPreferences.Settings(true, true))
        val valid = file.readBytes()
        for (bad in listOf(
            byteArrayOf(), valid.copyOf(8), valid + 0.toByte(),
            valid.copyOf().apply { this[0] = 0 },
            valid.copyOf().apply { this[lastIndex] = 7 },
            ByteArray(1024 * 1024) { 1 },
        )) {
            file.writeBytes(bad)
            assertEquals(defaults, preferences.load())
        }
    }

    @Test fun resetDeletesActiveBackupAndPendingStateAcrossRecreation() {
        val file = file()
        val preferences = BrowserHttpsPreferences(file)
        preferences.save(BrowserHttpsPreferences.Settings(true, true))
        val backup = File(file.path + ".bak").apply { writeBytes(file.readBytes()) }
        val pending = File(file.path + ".new").apply { writeBytes(file.readBytes()) }
        preferences.reset()
        assertFalse(file.exists())
        assertFalse(backup.exists())
        assertFalse(pending.exists())
        assertEquals(defaults, BrowserHttpsPreferences(file).load())
        preferences.reset()
    }

    @Test fun productionConstructorUsesNoBackupDirectoryAndNeverSharedPreferences() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = BrowserHttpsPreferences(context)
        try {
            preferences.save(BrowserHttpsPreferences.Settings(true, false))
            assertTrue(File(context.noBackupFilesDir, "browser-https-settings.bin").isFile)
            assertFalse(File(context.filesDir, "browser-https-settings.bin").exists())
            assertEquals(BrowserHttpsPreferences.Settings(true, false), BrowserHttpsPreferences(context).load())
        } finally { preferences.reset() }
    }

    @Test fun impossibleSaveFailsWithFixedCodeAndNoSensitiveCause() {
        val parent = temporary.newFile()
        val preferences = BrowserHttpsPreferences(File(parent, "settings.bin"))
        try {
            preferences.save(BrowserHttpsPreferences.Settings(true, true))
            fail("Expected failure")
        } catch (failure: BrowserHttpsPreferences.Failure) {
            assertEquals("HTTPS_SETTINGS_SAVE_FAILED", failure.code)
            assertNull(failure.cause)
        }
        assertEquals(defaults, preferences.load())
    }
}
