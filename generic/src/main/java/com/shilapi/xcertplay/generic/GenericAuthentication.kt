package com.shilapi.xcertplay.generic

import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File

/** Runtime provisioner for the two explicitly selected local build assets; never a remote fallback. */
internal object GenericAuthentication {
    @Synchronized fun ensureLocal(context: Context) {
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (target.isDirectory) {
            LocalMfiAuthenticationClient.load(target)
            return
        }
        val staging = File(context.noBackupFilesDir, "generic-auth-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare local authentication" }
        staging.setReadable(false, false); staging.setReadable(true, true)
        staging.setWritable(false, false); staging.setWritable(true, true)
        staging.setExecutable(false, false); staging.setExecutable(true, true)
        try {
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                val bytes = context.assets.open("offline-mfi/$name").use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 16384) { "Local authentication file is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray().also { check(it.isNotEmpty()) { "Local authentication file is empty" } }
                }
                try {
                    File(staging, name).apply {
                        writeBytes(bytes)
                        setReadable(false, false); setReadable(true, true)
                        setWritable(false, false); setWritable(true, true)
                    }
                } finally { bytes.fill(0) }
            }
            LocalMfiAuthenticationClient.load(staging)
            check(staging.renameTo(target)) { "Could not install local authentication" }
        } finally { staging.deleteRecursively() }
    }
}
