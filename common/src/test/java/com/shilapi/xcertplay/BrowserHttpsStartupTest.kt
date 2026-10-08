package com.shilapi.xcertplay

import android.system.ErrnoException
import android.system.OsConstants
import com.shilapi.xcertplay.browser.BrowserHttpsStartup
import com.shilapi.xcertplay.browser.BrowserHttpsStartup.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.BindException
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserHttpsStartupTest {
    @Test fun startupFailuresKeepTheirStageWithoutProviderMessages() {
        for (stage in Stage.values()) {
            assertEquals("${stage.name}_IO_FAILED",
                BrowserHttpsStartup.failureCode(stage, IOException("private provider detail")))
        }
        assertEquals("VIEWER_ASSETS_IO_FAILED", BrowserHttpsStartup.failureCode(
            Stage.VIEWER_ASSETS, java.io.FileNotFoundException("private file path")))
        assertEquals("TLS_VALIDATION_TLS_FAILED", BrowserHttpsStartup.failureCode(
            Stage.TLS_VALIDATION, SSLException("certificate subject")))
        assertEquals("VPN_PRIMARY_PERMISSION_DENIED", BrowserHttpsStartup.failureCode(
            Stage.VPN_PRIMARY, SecurityException("account details")))
    }

    @Test fun bindDiagnosticsRetainActualNestedErrnoWithoutGuessing() {
        for (errno in listOf(OsConstants.EADDRNOTAVAIL, OsConstants.EADDRINUSE, OsConstants.EACCES)) {
            val failure = BindException("secret input").apply {
                initCause(IOException("private path", ErrnoException("bind", errno)))
            }
            assertEquals("LISTENER_BIND_BIND_FAILED_ERRNO_$errno",
                BrowserHttpsStartup.failureCode(Stage.LISTENER_BIND, failure))
        }
        assertEquals("LISTENER_BIND_BIND_FAILED", BrowserHttpsStartup.failureCode(
            Stage.LISTENER_BIND, BindException("Permission denied: do not infer errno from this text")))
    }

    @Test fun exceptionTypeChainIsAllowlistedWithoutRawClassNamesOrMessages() {
        val failure = BindException("private address").apply {
            initCause(IOException("private path", ErrnoException("private operation", OsConstants.EADDRINUSE)))
        }
        assertEquals("BindException -> IOException -> ErrnoException", BrowserHttpsStartup.causeTypes(failure))
        assertEquals("NullPointerException", BrowserHttpsStartup.causeTypes(NullPointerException("private data")))
        assertEquals("RuntimeException", BrowserHttpsStartup.causeTypes(object : RuntimeException("private data") {}))
    }

    @Test fun unexpectedErrorsAreBoundedAndNeverExportTheirMessages() {
        val first = RuntimeException("private key text")
        val second = RuntimeException("credential URI")
        first.initCause(second); second.initCause(first)
        assertEquals("CONFLICT_CHECK_FAILED", BrowserHttpsStartup.failureCode(Stage.CONFLICT_CHECK, first))
        assertEquals(8, BrowserHttpsStartup.causeTypes(first).split(" -> ").size)
    }
}
