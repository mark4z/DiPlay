package com.shilapi.xcertplay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import com.shilapi.xcertplay.browser.BrowserHttpsPolicy
import com.shilapi.xcertplay.browser.BrowserTlsBundle
import com.shilapi.xcertplay.browser.BrowserTlsIdentity
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Phone-local, no-backup TLS identity storage. Only authenticated ciphertext reaches disk.
 *
 * An import ticket must be created before starting the provider read. Prepare on a worker, then
 * commit only after ALL provider resources have closed successfully. Cancel/recreation must close
 * the ticket, independently of the provider's cancellation/close callbacks. Close and commit are
 * serialized, so a late provider callback cannot commit a cancelled ticket. Tickets are never
 * restored across process death. The caller must wipe its input ZIP after prepare returns.
 *
 * Only this class creates the app-scoped, non-exportable Android Keystore encryption key. No URI,
 * filename, certificate text, private key text or underlying exception is saved or logged.
 */
internal class BrowserTlsStore internal constructor(
    private val directory: File,
    private val keys: Keys,
    private val validate: (ByteArray) -> BrowserTlsIdentity,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY),
        AndroidKeys(),
        { BrowserTlsBundle.read(it, BrowserHttpsPolicy.HOSTNAME) },
    )

    class Failure(val code: String) : IOException(code)

    /** Internal seam permits synthetic tests without exporting or mocking production keys. */
    internal interface Keys {
        fun existing(): SecretKey?
        fun getOrCreate(): SecretKey
        fun delete()
    }

    private class State {
        var generation = 0L
        var revision = 0L
        val pendingNames = mutableSetOf<String>()
    }

    private val state = synchronized(LOCK) {
        states.getOrPut(directory.absolutePath) { State() }
    }
    private val active get() = File(directory, ACTIVE)

    /** In-process change token for discarding identities cached by another Activity/controller. */
    fun revision(): Long = synchronized(LOCK) { state.revision }

    /** Calling this alone neither creates a key nor saves an identity. */
    fun beginImport(): PendingImport = synchronized(LOCK) {
        cleanOrphanStages()
        val name = "pending-${UUID.randomUUID()}.sealed"
        state.pendingNames.add(name)
        PendingImport(name, state.generation)
    }

    inner class PendingImport internal constructor(
        private val name: String,
        private val generation: Long,
    ) : Closeable {
        private var phase = Phase.NEW
        private val staging get() = File(directory, name)

        /** Validates a private in-memory copy before encrypting or writing anything. */
        fun prepare(zip: ByteArray): BrowserTlsIdentity {
            synchronized(LOCK) {
                if (!current() || phase != Phase.NEW) throw Failure("TLS_IMPORT_CANCELLED")
                phase = Phase.PREPARING
            }
            var plaintext: ByteArray? = null
            var sealed: ByteArray? = null
            try {
                if (zip.isEmpty() || zip.size > BrowserTlsBundle.MAX_ARCHIVE_BYTES) {
                    throw Failure("TLS_ARCHIVE_SIZE_INVALID")
                }
                plaintext = zip.copyOf()
                val identity = validate(plaintext)
                // Key creation is serialized with deletion. A cancelled/old generation never
                // recreates the deleted alias. Crypto itself need not hold the filesystem lock.
                val key = synchronized(LOCK) {
                    requirePreparing()
                    keys.getOrCreate()
                }
                sealed = encrypt(plaintext, key)
                synchronized(LOCK) {
                    requirePreparing()
                    if (!directory.isDirectory && !directory.mkdirs()) throw Failure("TLS_STORE_SAVE_FAILED")
                    FileOutputStream(staging).use { output ->
                        output.write(sealed)
                        output.fd.sync()
                    }
                    phase = Phase.PREPARED
                }
                return identity
            } catch (failure: BrowserTlsBundle.Failure) {
                discardAfterFailure()
                throw failure
            } catch (failure: Failure) {
                discardAfterFailure()
                throw failure
            } catch (_: Exception) {
                discardAfterFailure()
                throw Failure("TLS_STORE_SAVE_FAILED")
            } finally {
                plaintext?.fill(0)
                sealed?.fill(0)
            }
        }

        /**
         * Atomic same-directory replacement. Returns false for cancelled, deleted, unprepared or
         * already-committed tickets. The old active file is untouched on every pre-rename failure.
         * Call from the same lifecycle owner that handles cancellation, after provider close.
         */
        fun commit(): Boolean = synchronized(LOCK) {
            if (!current() || phase != Phase.PREPARED) return@synchronized false
            try {
                Os.rename(staging.absolutePath, active.absolutePath)
            } catch (_: Exception) {
                throw Failure("TLS_STORE_COMMIT_FAILED")
            }
            phase = Phase.COMMITTED
            state.revision++
            state.pendingNames.remove(name)
            true
        }

        /** Idempotent cancellation; never deletes an identity already successfully committed. */
        override fun close() = synchronized(LOCK) {
            if (phase == Phase.COMMITTED) return@synchronized
            phase = Phase.CLOSED
            state.pendingNames.remove(name)
            if (staging.exists() && !staging.delete()) throw Failure("TLS_STORE_DELETE_FAILED")
        }

        private fun current() = generation == state.generation &&
            phase != Phase.CLOSED && phase != Phase.COMMITTED

        private fun requirePreparing() {
            if (!current() || phase != Phase.PREPARING) throw Failure("TLS_IMPORT_CANCELLED")
        }

        private fun discardAfterFailure() {
            // A failed cleanup leaves ciphertext only. load/beginImport/delete retry its removal.
            try { close() } catch (_: Failure) { }
        }
    }

    /** Every load checks AEAD integrity, ZIP limits, current certificate validity/trust and hostname. */
    fun load(): BrowserTlsIdentity? = synchronized(LOCK) {
        var sealed: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            cleanOrphanStages()
            if (!active.exists()) return@synchronized null
            sealed = readSealed(active)
            val key = keys.existing() ?: throw Failure("TLS_STORE_KEY_UNAVAILABLE")
            plaintext = decrypt(sealed, key)
            validate(plaintext)
        } catch (failure: BrowserTlsBundle.Failure) {
            throw failure
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("TLS_STORE_LOAD_FAILED")
        } finally {
            sealed?.fill(0)
            plaintext?.fill(0)
        }
    }

    /** Invalidates even in-flight prepares, removes all encrypted files, and deletes the key alias. */
    fun delete() = synchronized(LOCK) {
        state.generation++
        state.revision++
        state.pendingNames.clear()
        var failed = false
        try {
            if (directory.exists()) {
                val files = directory.listFiles()
                if (files == null) failed = true
                else for (file in files) {
                    if ((file.name == ACTIVE || isStage(file.name)) && !file.delete()) failed = true
                }
            }
        } catch (_: Exception) { failed = true }
        // Always attempt cryptographic erasure, including when a filesystem delete failed.
        try { keys.delete() } catch (_: Exception) { failed = true }
        if (failed) throw Failure("TLS_STORE_DELETE_FAILED")
    }

    private fun cleanOrphanStages() {
        if (!directory.exists()) return
        val files = directory.listFiles() ?: throw Failure("TLS_STORE_READ_FAILED")
        for (file in files) {
            if (isStage(file.name) && file.name !in state.pendingNames && !file.delete()) {
                throw Failure("TLS_STORE_DELETE_FAILED")
            }
        }
    }

    private fun readSealed(file: File): ByteArray = FileInputStream(file).use { input ->
        val length = input.channel.size()
        if (length < MIN_SEALED_BYTES || length > MAX_SEALED_BYTES) throw Failure("TLS_STORE_SIZE_INVALID")
        val bytes = ByteArray(length.toInt())
        try {
            var at = 0
            while (at < bytes.size) {
                val count = input.read(bytes, at, bytes.size - at)
                if (count <= 0) throw Failure("TLS_STORE_READ_FAILED")
                at += count
            }
            if (input.read() != -1) throw Failure("TLS_STORE_SIZE_INVALID")
            bytes
        } catch (failure: Exception) {
            bytes.fill(0)
            throw failure
        }
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore generates a fresh random IV; callers never supply encryption nonces.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val nonce = cipher.iv
        if (nonce.size != NONCE_BYTES) throw Failure("TLS_STORE_SAVE_FAILED")
        val ciphertext = cipher.doFinal(plaintext)
        try {
            if (ciphertext.size != plaintext.size + TAG_BYTES) throw Failure("TLS_STORE_SIZE_INVALID")
            return HEADER + nonce + ciphertext
        } finally { ciphertext.fill(0) }
    }

    private fun decrypt(sealed: ByteArray, key: SecretKey): ByteArray {
        if (sealed.size !in MIN_SEALED_BYTES..MAX_SEALED_BYTES ||
            !HEADER.indices.all { sealed[it] == HEADER[it] }) throw Failure("TLS_STORE_FORMAT_INVALID")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, HEADER.size, NONCE_BYTES))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(sealed, HEADER.size + NONCE_BYTES, sealed.size - HEADER.size - NONCE_BYTES)
        if (plaintext.isEmpty() || plaintext.size > BrowserTlsBundle.MAX_ARCHIVE_BYTES) {
            plaintext.fill(0)
            throw Failure("TLS_STORE_SIZE_INVALID")
        }
        return plaintext
    }

    private class AndroidKeys : Keys {
        private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun existing(): SecretKey? = store().getKey(KEY_ALIAS, null) as? SecretKey
        override fun getOrCreate(): SecretKey {
            val store = store()
            if (store.containsAlias(KEY_ALIAS)) {
                return store.getKey(KEY_ALIAS, null) as? SecretKey ?: throw Failure("TLS_STORE_KEY_UNAVAILABLE")
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
        }
        override fun delete() { store().deleteEntry(KEY_ALIAS) }
    }

    private enum class Phase { NEW, PREPARING, PREPARED, COMMITTED, CLOSED }

    companion object {
        private const val DIRECTORY = "browser-https-identity"
        private const val ACTIVE = "identity.sealed"
        private const val KEY_ALIAS = "com.shilapi.xcertplay.browser.https.storage.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private val HEADER = "DIPTLS01".toByteArray(Charsets.US_ASCII)
        private val AAD = HEADER + BrowserHttpsPolicy.HOSTNAME.toByteArray(Charsets.US_ASCII)
        internal val MAX_SEALED_BYTES = HEADER.size + NONCE_BYTES + TAG_BYTES + BrowserTlsBundle.MAX_ARCHIVE_BYTES
        private val MIN_SEALED_BYTES = HEADER.size + NONCE_BYTES + TAG_BYTES + 1
        private val LOCK = Any()
        private val states = mutableMapOf<String, State>()
        private fun isStage(name: String) = name.startsWith("pending-") && name.endsWith(".sealed")
    }
}
