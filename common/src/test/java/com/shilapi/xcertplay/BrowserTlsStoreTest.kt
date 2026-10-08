package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.browser.BrowserTlsBundle
import com.shilapi.xcertplay.browser.BrowserTlsIdentity
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic bytes and runtime-generated AES keys only; never reads a real TLS ZIP or private key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserTlsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = mock(BrowserTlsIdentity::class.java)
    private val bytes get() = "synthetic-local-archive-marker-0123456789".toByteArray()

    private class TestKeys : BrowserTlsStore.Keys {
        var key: SecretKey? = null
        var creates = 0
        var deletes = 0
        override fun existing() = key
        override fun getOrCreate(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply {
            init(256)
        }.generateKey().also { key = it; creates++ }
        override fun delete() { key = null; deletes++ }
    }

    private fun directory() = File(temporary.newFolder(), "identity")
    private fun store(directory: File, keys: TestKeys) = BrowserTlsStore(directory, keys) { identity }
    private fun commitArchive(store: BrowserTlsStore, archive: ByteArray = bytes) {
        store.beginImport().use { ticket ->
            ticket.prepare(archive)
            assertTrue(ticket.commit())
        }
    }
    private fun active(directory: File) = File(directory, "identity.sealed")
    private fun stages(directory: File) = directory.listFiles()?.filter { it.name.startsWith("pending-") }.orEmpty()

    @Test fun savesOnlyCiphertextAndRevalidatesEveryLoadAcrossNewStoreInstances() {
        val directory = directory()
        val keys = TestKeys()
        var validations = 0
        var validationInput: ByteArray? = null
        fun instance() = BrowserTlsStore(directory, keys) {
            validations++
            assertArrayEquals(bytes, it)
            validationInput = it
            identity
        }
        val store = instance()
        val source = bytes
        val ticket = store.beginImport()
        assertSame(identity, ticket.prepare(source))
        assertNull(store.load())
        assertFalse(stages(directory).single().readText().contains(String(bytes)))
        assertTrue(validationInput!!.all { it == 0.toByte() })
        assertArrayEquals(bytes, source) // caller owns and wipes its original independently
        assertTrue(ticket.commit())
        assertFalse(ticket.commit())
        ticket.close()
        assertTrue(stages(directory).isEmpty())
        assertFalse(active(directory).readText().contains(String(bytes)))
        assertSame(identity, instance().load())
        assertSame(identity, instance().load())
        assertEquals(3, validations)
        assertEquals(1, keys.creates)
        assertTrue(validationInput!!.all { it == 0.toByte() })
    }

    @Test fun rejectedAndCancelledReplacementPreservePreviousActiveBytes() {
        val directory = directory()
        val keys = TestKeys()
        val store = store(directory, keys)
        commitArchive(store)
        val saved = active(directory).readBytes()
        val reject = BrowserTlsStore(directory, keys) { throw IllegalArgumentException("must not escape") }
        val invalid = reject.beginImport()
        assertFailure("TLS_STORE_SAVE_FAILED") { invalid.prepare(bytes) }
        assertFalse(invalid.commit())
        val cancelled = store.beginImport()
        cancelled.prepare(bytes + 1.toByte())
        cancelled.close()
        assertFalse(cancelled.commit()) // late provider-close callback cannot promote it
        cancelled.close()
        assertArrayEquals(saved, active(directory).readBytes())
        assertTrue(stages(directory).isEmpty())
        assertSame(identity, store.load())
    }

    @Test fun allInstancesObserveCommittedAndDeletedIdentityRevisionsOnly() {
        val directory = directory()
        val keys = TestKeys()
        val first = store(directory, keys)
        val second = store(directory, keys)
        val before = first.revision()
        val pending = first.beginImport()
        pending.prepare(bytes)
        assertEquals(before, second.revision())
        assertTrue(pending.commit())
        assertEquals(before + 1, second.revision())
        second.beginImport().use { cancelled -> cancelled.prepare(bytes) }
        assertEquals(before + 1, first.revision())
        second.delete()
        assertEquals(before + 2, first.revision())
        assertEquals(first.revision(), second.revision())
    }

    @Test fun cancellingWhileValidationIsBlockedPreventsLateKeyCreationAndWrites() {
        val directory = directory()
        val keys = TestKeys()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val store = BrowserTlsStore(directory, keys) {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            identity
        }
        val ticket = store.beginImport()
        val worker = Thread {
            try { ticket.prepare(bytes) } catch (failure: Throwable) { result.set(failure) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ticket.close()
        } finally { release.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertEquals("TLS_IMPORT_CANCELLED", (result.get() as BrowserTlsStore.Failure).code)
        assertFalse(ticket.commit())
        assertEquals(0, keys.creates)
        assertFalse(directory.exists())
    }

    @Test fun deleteInvalidatesPreparedAndStillValidatingTicketsFromOtherInstances() {
        val directory = directory()
        val keys = TestKeys()
        val store = store(directory, keys)
        commitArchive(store)
        val prepared = store.beginImport()
        prepared.prepare(bytes)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val other = BrowserTlsStore(directory, keys) {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            identity
        }
        val pending = other.beginImport()
        val worker = Thread {
            try { pending.prepare(bytes) } catch (failure: Throwable) { result.set(failure) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            store.delete()
        } finally { release.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertEquals("TLS_IMPORT_CANCELLED", (result.get() as BrowserTlsStore.Failure).code)
        assertFalse(prepared.commit())
        assertFalse(pending.commit())
        assertNull(keys.key)
        assertEquals(1, keys.deletes)
        assertEquals(1, keys.creates)
        assertFalse(active(directory).exists())
        assertTrue(stages(directory).isEmpty())
        assertNull(store.load())
        // A new explicit import after deletion creates a fresh key and works normally.
        commitArchive(store)
        assertEquals(2, keys.creates)
        assertSame(identity, store.load())
    }

    @Test fun commitFailureNeverReplacesThePreviousIdentity() {
        val directory = directory()
        val keys = TestKeys()
        val store = store(directory, keys)
        commitArchive(store)
        val saved = active(directory).readBytes()
        val pending = store.beginImport()
        pending.prepare(bytes)
        assertTrue(stages(directory).single().delete())
        assertFailure("TLS_STORE_COMMIT_FAILED") { pending.commit() }
        pending.close()
        assertArrayEquals(saved, active(directory).readBytes())
    }

    @Test fun ciphertextTamperingWrongKeyAndMissingKeyFailClosedWithoutReplacement() {
        val directory = directory()
        val keys = TestKeys()
        val store = store(directory, keys)
        commitArchive(store)
        val saved = active(directory).readBytes()
        val corrupted = saved.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        active(directory).writeBytes(corrupted)
        assertFailure("TLS_STORE_LOAD_FAILED") { store.load() }
        assertArrayEquals(corrupted, active(directory).readBytes())
        active(directory).writeBytes(saved)
        keys.key = null
        assertFailure("TLS_STORE_KEY_UNAVAILABLE") { store.load() }
        assertEquals(1, keys.creates) // loading cannot replace a missing alias
        keys.getOrCreate()
        assertFailure("TLS_STORE_LOAD_FAILED") { store.load() }
        assertArrayEquals(saved, active(directory).readBytes())
    }

    @Test fun changedValidityOrTrustIsCheckedAgainAndPlaintextIsWipedOnFailure() {
        val directory = directory()
        val keys = TestKeys()
        commitArchive(store(directory, keys))
        var plaintext: ByteArray? = null
        val reject = BrowserTlsStore(directory, keys) {
            plaintext = it
            throw IllegalArgumentException("synthetic trust failure")
        }
        assertFailure("TLS_STORE_LOAD_FAILED") { reject.load() }
        assertTrue(plaintext!!.all { it == 0.toByte() })
        assertTrue(active(directory).exists())
    }

    @Test fun limitsInputBeforeValidationAndCiphertextBeforeDecryption() {
        val directory = directory()
        val keys = TestKeys()
        var validations = 0
        val store = BrowserTlsStore(directory, keys) { validations++; identity }
        for (input in listOf(byteArrayOf(), ByteArray(BrowserTlsBundle.MAX_ARCHIVE_BYTES + 1))) {
            store.beginImport().use { pending ->
                assertFailure("TLS_ARCHIVE_SIZE_INVALID") { pending.prepare(input) }
                assertFalse(pending.commit())
            }
        }
        assertEquals(0, validations)
        assertEquals(0, keys.creates)
        commitArchive(store, ByteArray(BrowserTlsBundle.MAX_ARCHIVE_BYTES) { 42 })
        assertEquals(BrowserTlsStore.MAX_SEALED_BYTES.toLong(), active(directory).length())
        assertSame(identity, store.load())
        RandomAccessFile(active(directory), "rw").use { it.setLength(BrowserTlsStore.MAX_SEALED_BYTES + 1L) }
        assertFailure("TLS_STORE_SIZE_INVALID") { store.load() }
        active(directory).writeBytes(byteArrayOf(1))
        assertFailure("TLS_STORE_SIZE_INVALID") { store.load() }
    }

    @Test fun orphanEncryptedStagesAreRemovedButLivePreparedStagesAreRetained() {
        val directory = directory()
        val keys = TestKeys()
        val store = store(directory, keys)
        val ticket = store.beginImport()
        ticket.prepare(bytes)
        val live = stages(directory).single()
        val orphan = File(directory, "pending-abandoned.sealed").apply { writeBytes(byteArrayOf(9)) }
        assertNull(store(directory, keys).load())
        assertFalse(orphan.exists())
        assertTrue(live.exists())
        ticket.close() // equivalent to the old activity being destroyed before recreation
        assertNull(store(directory, keys).load())
        assertFalse(ticket.commit())
    }

    @Test fun deleteAlsoTriesCryptographicErasureIfAFileCannotBeRemoved() {
        val directory = directory()
        val keys = TestKeys()
        val store = store(directory, keys)
        commitArchive(store)
        val blocked = File(directory, "pending-blocked.sealed").apply { mkdir() }
        File(blocked, "child").writeBytes(byteArrayOf(1))
        assertFailure("TLS_STORE_DELETE_FAILED") { store.delete() }
        assertNull(keys.key)
        assertEquals(1, keys.deletes)
        assertFalse(active(directory).exists())
    }

    @Test fun productionConstructorRejectsInvalidZipBeforeAnyKeystoreOrDiskWrite() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = BrowserTlsStore(context)
        store.beginImport().use { pending ->
            try {
                pending.prepare(bytes)
                fail("Invalid ZIP unexpectedly validated")
            } catch (failure: BrowserTlsBundle.Failure) {
                assertEquals("ZIP_MALFORMED", failure.code)
            }
            assertFalse(pending.commit())
        }
        assertFalse(File(context.noBackupFilesDir, "browser-https-identity/identity.sealed").exists())
    }

    private fun assertFailure(code: String, action: () -> Any?) {
        try {
            action()
            fail("Expected $code")
        } catch (failure: BrowserTlsStore.Failure) {
            assertEquals(code, failure.code)
            assertEquals(code, failure.message)
            assertNull(failure.cause)
        }
    }
}
