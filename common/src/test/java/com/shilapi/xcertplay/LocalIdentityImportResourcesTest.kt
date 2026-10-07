package com.shilapi.xcertplay

import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocalIdentityImportResourcesTest {
    @Test fun importIsLocalizedInEverySupportedLanguageAndPreservesFilenames() {
        val titles = mapOf(
            "en" to "Import local identity", "zh-CN" to "导入本地身份", "zh-TW" to "匯入本機身分",
            "ar" to "استيراد هوية محلية", "ru" to "Импорт локальной идентичности",
            "uk" to "Імпорт локальної ідентичності", "es" to "Importar identidad local",
        )
        val messages = listOf(
            R.string.identity_import_title, R.string.identity_import_installed, R.string.identity_import_none,
            R.string.identity_import_compatibility, R.string.identity_import_explanation,
            R.string.identity_import_certificate_step, R.string.identity_import_reading_key,
            R.string.identity_import_validating, R.string.identity_import_busy,
            R.string.identity_import_disconnect_required, R.string.identity_import_disconnect_first,
            R.string.identity_import_disconnected, R.string.identity_import_cancelled,
            R.string.identity_import_interrupted, R.string.identity_import_failed,
            R.string.identity_import_picker_unavailable, R.string.identity_import_success,
            R.string.identity_import_saved_not_ready, R.string.identity_import_recovery_failed,
        )
        val base = RuntimeEnvironment.getApplication()
        for ((tag, title) in titles) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            assertEquals(tag, title, context.getString(R.string.identity_import_action))
            assertTrue(context.getString(R.string.identity_import_choose_key).contains("identity.pk8"))
            assertTrue(context.getString(R.string.identity_import_choose_certificate).contains("certificate.p7b"))
            assertTrue(context.getString(R.string.identity_import_explanation).contains("16 KiB"))
            assertTrue(context.getString(R.string.setup_error_auth).contains(title))
            for (message in messages) assertFalse("$tag / $message", context.getString(message).isBlank())
        }
    }
}
