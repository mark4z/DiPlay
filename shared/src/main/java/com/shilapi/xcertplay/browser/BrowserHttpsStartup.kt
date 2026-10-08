package com.shilapi.xcertplay.browser

import android.system.ErrnoException
import java.io.IOException
import java.net.BindException
import javax.net.ssl.SSLException

/** Fixed categories only. Never include provider messages, certificate details or input bytes. */
object BrowserHttpsStartup {
    enum class Stage { PREFLIGHT, TLS_VALIDATION, CONFLICT_CHECK, VPN_PRIMARY, VPN_COMPATIBILITY,
        VIEWER_ASSETS, LISTENER_BIND, SELF_CHECK }

    fun causeTypes(failure: Exception): String {
        val types = mutableListOf<String>()
        var cause: Throwable? = failure
        repeat(8) {
            val current = cause ?: return@repeat
            // An allowlist, not class names/messages supplied by arbitrary providers.
            types += when (current) {
                is ErrnoException -> "ErrnoException"
                is SecurityException -> "SecurityException"
                is SSLException -> "SSLException"
                is BindException -> "BindException"
                is java.net.SocketException -> "SocketException"
                is java.io.FileNotFoundException -> "FileNotFoundException"
                is IOException -> "IOException"
                is NullPointerException -> "NullPointerException"
                is IllegalArgumentException -> "IllegalArgumentException"
                is IllegalStateException -> "IllegalStateException"
                is UnsupportedOperationException -> "UnsupportedOperationException"
                is RuntimeException -> "RuntimeException"
                else -> "Exception"
            }
            cause = current.cause
        }
        return types.joinToString(" -> ")
    }

    fun failureCode(stage: Stage, failure: Exception): String {
        var cause: Throwable? = failure
        var errno: Int? = null
        var permission = false
        var bind = false
        var tls = false
        var io = false
        // Bound traversal even for a malformed/cyclic exception chain.
        repeat(8) {
            val current = cause ?: return@repeat
            if (current is ErrnoException) errno = current.errno
            permission = permission || current is SecurityException
            bind = bind || current is BindException
            tls = tls || current is SSLException
            io = io || current is IOException
            cause = current.cause
        }
        val category = when {
            permission -> "PERMISSION_DENIED"
            tls -> "TLS_FAILED"
            bind -> "BIND_FAILED"
            io -> "IO_FAILED"
            failure is IllegalArgumentException -> "INVALID_STATE"
            failure is IllegalStateException -> "INVALID_STATE"
            else -> "FAILED"
        }
        return "${stage.name}_$category" + (errno?.let { "_ERRNO_$it" } ?: "")
    }
}
