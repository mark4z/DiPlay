package com.shilapi.xcertplay

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Small, private no-backup settings. Missing, unreadable or malformed data always fails closed. */
internal class BrowserHttpsPreferences internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, FILENAME))

    data class Settings(
        val autoStartOnOpen: Boolean = false,
        val autoAllowConnections: Boolean = false,
    )

    class Failure(val code: String) : IOException(code)

    fun load(): Settings = synchronized(LOCK) {
        try {
            AtomicFile(file).openRead().use { input ->
                // Exactly one small versioned record; reject truncation, trailing data and flags.
                val bytes = ByteArray(HEADER.size + 1)
                var at = 0
                while (at < bytes.size) {
                    val count = input.read(bytes, at, bytes.size - at)
                    if (count <= 0) return@synchronized Settings()
                    at += count
                }
                if (input.read() != -1 || !HEADER.indices.all { bytes[it] == HEADER[it] }) return@synchronized Settings()
                val flags = bytes.last().toInt() and 255
                if (flags and 3 != flags) return@synchronized Settings()
                Settings(flags and 1 != 0, flags and 2 != 0)
            }
        } catch (_: Exception) { Settings() }
    }

    /** The caller enables either setting only in response to that setting's explicit user choice. */
    fun save(settings: Settings) = synchronized(LOCK) {
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            val parent = file.parentFile ?: throw Failure("HTTPS_SETTINGS_SAVE_FAILED")
            if (!parent.isDirectory && !parent.mkdirs()) throw Failure("HTTPS_SETTINGS_SAVE_FAILED")
            output = atomic.startWrite()
            output.write(HEADER)
            output.write((if (settings.autoStartOnOpen) 1 else 0) or (if (settings.autoAllowConnections) 2 else 0))
            atomic.finishWrite(output)
            output = null
            if (load() != settings) throw Failure("HTTPS_SETTINGS_SAVE_FAILED")
        } catch (_: Exception) {
            if (output != null) atomic.failWrite(output)
            throw Failure("HTTPS_SETTINGS_SAVE_FAILED")
        }
    }

    /** Deletes active, backup and unfinished AtomicFile records, restoring both false defaults. */
    fun reset() = synchronized(LOCK) {
        try {
            AtomicFile(file).delete()
            // Also cover leftovers created by another Android AtomicFile implementation/version.
            for (candidate in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) {
                if (candidate.exists() && !candidate.delete()) throw Failure("HTTPS_SETTINGS_DELETE_FAILED")
            }
        } catch (_: Exception) { throw Failure("HTTPS_SETTINGS_DELETE_FAILED") }
    }

    companion object {
        private const val FILENAME = "browser-https-settings.bin"
        private val HEADER = "DIPSET01".toByteArray(Charsets.US_ASCII)
        private val LOCK = Any()
    }
}
