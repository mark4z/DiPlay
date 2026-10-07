package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mfi.LocalMfiIdentityStore
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/** Uses only synthetic bytes and local provider fixtures. Never loads a real accessory identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
class LocalIdentityImportUiTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity

    @Before fun create() {
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopping", false)
        ReflectionHelpers.setField(DiPlayBootstrap, "ready", false)
        controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        activity = controller.get()
    }

    @After fun destroy() {
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopping", false)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun homeOffersImportWhileConnectIsDisabledByMissingAuthentication() {
        assertNotNull(ReflectionHelpers.getField<String?>(activity, "setupError"))
        assertFalse(button(R.string.connect_phone).isEnabled)
        assertTrue(button(R.string.identity_import_action).isEnabled)
        assertTrue(texts().contains(activity.getString(R.string.identity_import_none)))
    }

    @Config(qualifiers = "en-w500dp-h400dp")
    @Test fun compactHomeOffersImportWhileConnectIsDisabled() {
        assertFalse(button(R.string.connect_phone).isEnabled)
        assertTrue(button(R.string.identity_import_action).isEnabled)
    }

    @Test fun connectionSetupAlsoOffersImport() {
        ReflectionHelpers.setField(activity, "page", "connection")
        call("render")
        assertTrue(button(R.string.identity_import_action).isEnabled)
    }

    @Test fun keyAndCertificateUseLocalSafWithNoBroadOrPersistentGrant() {
        for (privateKey in listOf(true, false)) {
            val intent = DiPlayActivity::class.java.getDeclaredMethod("identityDocumentIntent", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, privateKey) as Intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertEquals("*/*", intent.type)
            assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    @Test fun repeatedClicksCreateOnePickerAndCancelKeepsExistingPair() {
        val existing = installOpaqueExistingPair()
        beginKeyPicker()
        val first = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, first.intent.action)
        call("requestIdentityImport")
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        receive(Activity.RESULT_CANCELED, null, privateKey = true)
        assertNull(attempt())
        assertTrue(button(R.string.identity_import_action).isEnabled)
        assertExistingPair(existing)
        assertTrue(texts().contains(activity.getString(R.string.identity_import_cancelled)))
    }

    @Test fun keyIsStagedUntilUserExplicitlyChoosesCertificateAndCanBeCancelled() {
        val existing = installOpaqueExistingPair()
        beginKeyPicker()
        shadowOf(activity).nextStartedActivityForResult
        val keyUri = Uri.parse("content://local.synthetic/key")
        shadowOf(activity.contentResolver).registerInputStream(keyUri, ByteArrayInputStream(byteArrayOf(4, 5, 6)))
        receive(Activity.RESULT_OK, keyUri, privateKey = true)
        await { latestDialog().getButton(AlertDialog.BUTTON_POSITIVE)?.text == activity.getString(R.string.identity_import_choose_certificate) }
        assertExistingPair(existing)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val second = shadowOf(activity).nextStartedActivityForResult
        assertEquals(activity.getString(R.string.identity_import_choose_certificate), second.intent.getStringExtra(Intent.EXTRA_TITLE))
        receive(Activity.RESULT_CANCELED, null, privateKey = false)
        assertExistingPair(existing)
        await { importStagingDirectories().isEmpty() }
    }

    @Test fun activeSessionRequiresExplicitDisconnectAndNeverOpensPickerAutomatically() {
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { done ->
            stops++
            CarPlayBackgroundSession.clear()
            done()
        }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        call("requestIdentityImport")
        assertEquals(activity.getString(R.string.identity_import_disconnect_first), latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals(0, stops)
        assertNull(attempt())
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        latestDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(0, stops)
        call("requestIdentityImport")
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertTrue(texts().contains(activity.getString(R.string.identity_import_disconnected)))
    }

    @Test fun aSessionThatStartsWhilePickerIsOpenPreventsReadingAndCommit() {
        val existing = installOpaqueExistingPair()
        beginKeyPicker()
        val stop: ((() -> Unit) -> Unit) = { }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        receive(Activity.RESULT_OK, Uri.parse("content://local.synthetic/unread"), privateKey = true)
        assertNull(attempt())
        assertEquals(activity.getString(R.string.identity_import_disconnect_first), latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertExistingPair(existing)
        assertTrue(importStagingDirectories().isEmpty())
    }

    @Test fun finalCommitGuardRejectsAConnectionStartedAfterValidation() {
        val existing = installOpaqueExistingPair()
        val session = mock(LocalMfiIdentityStore.ImportSession::class.java)
        prepareCommit(session)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopping", true)
        invokeCommit()
        verify(session, never()).commit()
        assertNull(attempt())
        assertExistingPair(existing)
        assertEquals(activity.getString(R.string.identity_import_disconnect_first), latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).text)
    }

    @Test fun committedIdentityClearsSetupErrorAndPreservesSelectedModesWithoutConnecting() {
        // The store's synthetic-identity suite tests real validation/atomic writes. Mock only its
        // successful commit here; USB authentication lets bootstrap reload without asset fixtures.
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        val session = mock(LocalMfiIdentityStore.ImportSession::class.java)
        prepareCommit(session)
        invokeCommit()
        verify(session).commit()
        assertNull(ReflectionHelpers.getField<String?>(activity, "setupError"))
        assertTrue(button(R.string.connect_phone).isEnabled)
        assertTrue(button(R.string.identity_import_action).isEnabled)
        assertTrue(texts().contains(activity.getString(R.string.identity_import_installed)))
        assertTrue(texts().contains(activity.getString(R.string.identity_import_success)))
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(activity))
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun failedCommitAndFailedRecoveryKeepConnectDisabledWithGenericStatus() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        val session = mock(LocalMfiIdentityStore.ImportSession::class.java)
        doThrow(IllegalStateException("synthetic-private-storage-detail")).`when`(session).commit()
        prepareCommit(session)
        invokeCommit()
        verify(session).commit()
        assertNotNull(ReflectionHelpers.getField<String?>(activity, "setupError"))
        assertFalse(button(R.string.connect_phone).isEnabled)
        assertTrue(button(R.string.identity_import_action).isEnabled)
        assertTrue(texts().contains(activity.getString(R.string.identity_import_recovery_failed)))
        assertTrue(texts().none { "synthetic-private-storage-detail" in it })
    }

    @Test fun stoppingForPickerDoesNotCancelButDestroyInvalidatesAndIgnoresLateResult() {
        beginKeyPicker()
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop()
        assertNotNull(attempt())
        assertTrue(saved.getBoolean("identity_import_pending"))
        controller.destroy()
        controller = Robolectric.buildActivity(DiPlayActivity::class.java).create(saved).start().restoreInstanceState(saved).resume().visible()
        activity = controller.get()
        assertNull(attempt())
        assertTrue(texts().contains(activity.getString(R.string.identity_import_interrupted)))
        receive(Activity.RESULT_OK, Uri.parse("content://local.synthetic/stale"), privateKey = true)
        assertNull(attempt())
        assertTrue(importStagingDirectories().isEmpty())
        assertTrue(button(R.string.identity_import_action).isEnabled)
    }

    @Test fun cancellationClosesSlowProviderWithoutBlockingUiOrShowingLateStep() {
        val input = BlockingInput()
        beginKeyPicker()
        val uri = Uri.parse("content://local.synthetic/slow")
        shadowOf(activity.contentResolver).registerInputStream(uri, input)
        receive(Activity.RESULT_OK, uri, privateKey = true)
        assertTrue(input.entered.await(3, TimeUnit.SECONDS))
        val progress = latestDialog()
        val started = System.nanoTime()
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue("cancel must not wait for provider I/O", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
        assertNull(attempt())
        assertTrue(input.closed.await(3, TimeUnit.SECONDS))
        await { importStagingDirectories().isEmpty() }
        assertFalse(progress.isShowing)
        assertSame(progress, latestDialog())
    }

    @Test fun unreadableProviderShowsGenericFailureWithoutLoggingUriOrProviderText() {
        val marker = "synthetic-provider-private-detail"
        beginKeyPicker()
        val uri = Uri.parse("content://local.synthetic/$marker")
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException(marker)
        }
        shadowOf(activity.contentResolver).registerInputStream(uri, input)
        receive(Activity.RESULT_OK, uri, privateKey = true)
        await { attempt() == null }
        assertTrue(texts().contains(activity.getString(R.string.identity_import_failed)))
        assertTrue(texts().none { marker in it })
        assertTrue(ShadowLog.getLogs().none { marker in it.msg || it.throwable?.toString()?.contains(marker) == true })
    }

    @Test fun nonContentUriIsNeverOpened() {
        beginKeyPicker()
        receive(Activity.RESULT_OK, Uri.parse("https://invalid.example/identity.pk8"), privateKey = true)
        assertNull(attempt())
        assertTrue(texts().contains(activity.getString(R.string.identity_import_failed)))
        assertTrue(importStagingDirectories().isEmpty())
    }

    private fun beginKeyPicker() {
        call("requestIdentityImport")
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(attempt())
        assertFalse(button(R.string.identity_import_action).isEnabled)
    }

    private fun prepareCommit(session: LocalMfiIdentityStore.ImportSession) {
        beginKeyPicker()
        shadowOf(activity).nextStartedActivityForResult
        val owned = checkNotNull(attempt())
        ReflectionHelpers.setField(owned, "session", session)
        val step = owned.javaClass.getDeclaredField("step").apply { isAccessible = true }
        step.set(owned, step.type.enumConstants.single { it.toString() == "VALIDATING" })
    }

    private fun invokeCommit() {
        val owned = checkNotNull(attempt())
        DiPlayActivity::class.java.getDeclaredMethod("commitIdentityImport", owned.javaClass)
            .apply { isAccessible = true }.invoke(activity, owned)
    }

    private fun receive(code: Int, uri: Uri?, privateKey: Boolean) {
        DiPlayActivity::class.java.getDeclaredMethod("receiveIdentityDocument", Int::class.javaPrimitiveType, Uri::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, code, uri, privateKey)
    }

    private fun attempt(): Any? = ReflectionHelpers.getField(activity, "identityImportAttempt")
    private fun latestDialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
    private fun call(name: String) = DiPlayActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun button(id: Int): Button = descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text == activity.getString(id) }
    private fun texts(): List<String> = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun importStagingDirectories(): List<File> = activity.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("offline-mfi-import-") }

    private fun installOpaqueExistingPair(): File = File(activity.noBackupFilesDir, "offline-mfi").apply {
        mkdirs()
        File(this, "identity.pk8").writeBytes(byteArrayOf(11, 12, 13))
        File(this, "certificate.p7b").writeBytes(byteArrayOf(21, 22, 23))
    }

    private fun assertExistingPair(directory: File) {
        assertArrayEquals(byteArrayOf(11, 12, 13), File(directory, "identity.pk8").readBytes())
        assertArrayEquals(byteArrayOf(21, 22, 23), File(directory, "certificate.p7b").readBytes())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("background import did not settle", condition())
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private class BlockingInput : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val released = AtomicBoolean(false)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            entered.countDown()
            check(closed.await(5, TimeUnit.SECONDS))
            return if (released.get()) -1 else super.read(buffer, offset, length)
        }
        override fun close() {
            released.set(true)
            closed.countDown()
            super.close()
        }
    }
}
