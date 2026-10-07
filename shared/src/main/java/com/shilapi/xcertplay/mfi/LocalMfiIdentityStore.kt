package com.shilapi.xcertplay.mfi

import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Private, local-only provisioning. The host supplies streams from explicit user selections.
 * The live directory stays compatible with [LocalMfiAuthenticationClient.load].
 *
 * Call recovery before opening a local authenticator, and commit only while disconnected.
 * Files and directory renames are synced before the commit marker is written. An interrupted
 * replacement without that marker restores the previous directory on the next recovery.
 */
class LocalMfiIdentityStore internal constructor(
    private val root: File,
    private val files: FileOperations,
) {
    constructor(noBackupFilesDir: File) : this(noBackupFilesDir, AndroidFileOperations)

    private val live get() = File(root, LocalMfiAuthenticationClient.DIRECTORY)
    private val pending get() = File(root, PENDING)
    private val previous get() = File(root, PREVIOUS)

    fun recover() = synchronized(transactionLock) { recoverLocked() }

    fun hasImportedIdentity(): Boolean = synchronized(transactionLock) {
        recoverLocked()
        File(live, IMPORTED).isFile
    }

    /** Existing imported/manual installations take precedence over optional bundled assets. */
    fun ensureInstalled(openAsset: (String) -> InputStream) = synchronized(transactionLock) {
        recoverLocked()
        if (live.exists()) {
            LocalMfiAuthenticationClient.load(live)
            return@synchronized
        }
        beginSession(imported = false).use { session ->
            openAsset(KEY).use(session::writePrivateKey)
            openAsset(CERTIFICATE).use(session::writeCertificate)
            session.validate()
            session.commit()
        }
    }

    fun beginImport(): ImportSession = synchronized(transactionLock) {
        recoverLocked()
        beginSession(imported = true)
    }

    private fun beginSession(imported: Boolean): ImportSession {
        check(root.isDirectory) { "Local authentication storage is unavailable" }
        val staging = File(root, STAGING_PREFIX + UUID.randomUUID())
        check(staging.mkdir()) { "Could not prepare local authentication" }
        try {
            makePrivate(staging, directory = true)
            activeStages.add(staging.absolutePath)
            return ImportSession(staging, imported)
        } catch (failure: Exception) {
            staging.deleteRecursively()
            throw failure
        }
    }

    /** Streams are caller-owned. Close the session when a picker is cancelled or its host exits. */
    inner class ImportSession internal constructor(
        private val staging: File,
        private val imported: Boolean,
    ) : Closeable {
        @Volatile private var cancelled = false
        private var finished = false
        private var validated = false

        @Synchronized fun writePrivateKey(input: InputStream) = write(KEY, input)
        @Synchronized fun writeCertificate(input: InputStream) = write(CERTIFICATE, input)

        private fun write(name: String, input: InputStream) {
            checkOpen()
            validated = false
            val target = File(staging, name)
            // A failed replacement read must not leave an earlier staged file validatable.
            remove(target)
            val normalized = LocalMfiImportEncoding.read(input, name == KEY, imported, ::checkOpen)
            try {
                checkOpen()
                FileOutputStream(target).use { output ->
                    output.write(normalized)
                    checkOpen()
                    output.fd.sync()
                }
                makePrivate(target, directory = false)
            } finally {
                normalized.fill(0)
            }
        }

        /** Performs cryptographic validation off the main thread before the final UI check. */
        @Synchronized fun validate() {
            checkOpen()
            validated = false
            LocalMfiAuthenticationClient.load(staging)
            checkOpen()
            if (imported) writeMarker(File(staging, IMPORTED))
            writeMarker(File(staging, INSTALLING))
            files.syncDirectory(staging)
            validated = true
        }

        /** Only a previously validated, unchanged session can be committed. */
        @Synchronized fun commit() {
            checkOpen()
            check(validated) { "Local identity must be validated before import" }
            synchronized(transactionLock) {
                recoverLocked()
                checkOpen()
                var committed = false
                var replacementPublished = false
                try {
                    rename(staging, pending)
                    files.syncDirectory(root)
                    files.checkpoint(Checkpoint.PREPARED)
                    if (live.exists()) {
                        rename(live, previous)
                        files.syncDirectory(root)
                    }
                    files.checkpoint(Checkpoint.PREVIOUS_MOVED)
                    rename(pending, live)
                    replacementPublished = true
                    files.syncDirectory(root)
                    files.checkpoint(Checkpoint.REPLACEMENT_MOVED)
                    writeMarker(File(live, COMMITTED))
                    files.syncDirectory(live)
                    committed = true
                    finished = true
                    files.checkpoint(Checkpoint.COMMITTED)
                } catch (failure: Exception) {
                    if (!committed) {
                        // Do not claim failure after a durable commit. Before it, restore the old
                        // pair; if restoration itself fails, leave the backup for startup recovery.
                        try {
                            if (previous.exists()) {
                                rollbackLocked()
                            } else {
                                if (replacementPublished) {
                                    remove(live)
                                    files.syncDirectory(root)
                                }
                                remove(pending)
                            }
                        } catch (recoveryFailure: Exception) {
                            failure.addSuppressed(recoveryFailure)
                        }
                        finished = true
                        throw failure
                    }
                } finally {
                    activeStages.remove(staging.absolutePath)
                }
                // Backup cleanup is restartable and cannot turn a successful commit into failure.
                runCatching { remove(previous); files.syncDirectory(root) }
            }
        }

        override fun close() {
            cancelled = true
            synchronized(this) {
                finished = true
                synchronized(transactionLock) {
                    activeStages.remove(staging.absolutePath)
                    remove(staging)
                }
            }
        }

        private fun checkOpen() {
            check(!cancelled && !finished) { "Local identity import is no longer active" }
        }
    }

    private fun recoverLocked() {
        if (!root.exists()) return
        if (previous.exists()) {
            val committed = File(live, COMMITTED).isFile && runCatching {
                LocalMfiAuthenticationClient.load(live)
            }.isSuccess
            if (committed) remove(previous) else rollbackLocked()
            files.syncDirectory(root)
        } else if (File(live, INSTALLING).isFile && !File(live, COMMITTED).isFile) {
            // An interrupted first installation has no backup directory to signal recovery.
            remove(live)
            files.syncDirectory(root)
        }
        remove(pending)
        // Includes a pre-import version's interrupted asset copy. Never removes a live picker.
        root.listFiles()?.filter {
            (it.name.startsWith(STAGING_PREFIX) || it.name == "offline-mfi-staging") &&
                it.absolutePath !in activeStages
        }?.forEach(::remove)
    }

    private fun rollbackLocked() {
        if (previous.exists()) {
            remove(live)
            rename(previous, live)
            files.syncDirectory(root)
        }
        remove(pending)
    }

    private fun rename(from: File, to: File) {
        check(!to.exists() && files.rename(from, to)) { "Could not install local authentication" }
    }

    private fun remove(file: File) {
        check(!file.exists() || file.deleteRecursively()) { "Could not clean local authentication storage" }
    }

    private fun writeMarker(file: File) {
        FileOutputStream(file).use { output ->
            output.write(1)
            output.fd.sync()
        }
        makePrivate(file, directory = false)
    }

    private fun makePrivate(file: File, directory: Boolean) {
        check(file.setReadable(false, false) && file.setWritable(false, false) &&
            file.setExecutable(false, false) && file.setReadable(true, true) &&
            file.setWritable(true, true) && (!directory || file.setExecutable(true, true))) {
            "Could not protect local authentication storage"
        }
    }

    internal interface FileOperations {
        fun rename(from: File, to: File): Boolean = from.renameTo(to)
        fun syncDirectory(directory: File)
        fun checkpoint(checkpoint: Checkpoint) = Unit
    }

    internal enum class Checkpoint { PREPARED, PREVIOUS_MOVED, REPLACEMENT_MOVED, COMMITTED }

    private object AndroidFileOperations : FileOperations {
        override fun syncDirectory(directory: File) {
            check(directory.isDirectory) { "Local authentication storage is unavailable" }
            // O_DIRECTORY is not part of Android's public SDK. A read-only directory fd
            // still supports fsync on the app-private filesystem.
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }

    companion object {
        private const val KEY = "identity.pk8"
        private const val CERTIFICATE = "certificate.p7b"
        private const val IMPORTED = ".imported"
        private const val COMMITTED = ".committed"
        private const val INSTALLING = ".installing"
        private const val STAGING_PREFIX = "offline-mfi-import-"
        private const val PENDING = "offline-mfi-pending"
        private const val PREVIOUS = "offline-mfi-previous"
        private val transactionLock = Any()
        private val activeStages = mutableSetOf<String>()
    }
}
