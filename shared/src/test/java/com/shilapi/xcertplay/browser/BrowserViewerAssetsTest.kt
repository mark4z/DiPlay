package com.shilapi.xcertplay.browser

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BrowserViewerAssetsTest {
    private fun assets() = BrowserViewerAssets.load { ByteArrayInputStream("synthetic:$it".toByteArray()) }
    private fun request(path: String, method: String = "GET", origin: String? = null,
                        host: String = BrowserViewerAssets.AUTHORITY,
                        extra: Map<String, String> = emptyMap()): BrowserLanProtocol.HttpRequest =
        BrowserLanProtocol.HttpRequest(method, path, mapOf("host" to host) +
            (origin?.let { mapOf("origin" to it) } ?: emptyMap()) + extra)
    private fun classify(request: BrowserLanProtocol.HttpRequest) = BrowserLanProtocol.classifyRequest(
        request, BrowserViewerAssets.AUTHORITY, BrowserViewerAssets.ORIGIN, assets())

    @Test fun generatedBrowserConfigurationMatchesTlsPolicyAndIgnoresStaticDefault() {
        assertEquals(BrowserHttpsPolicy.HOSTNAME, BrowserViewerAssets.HOSTNAME)
        assertEquals(BrowserHttpsPolicy.VIEWER_URL.removeSuffix("/"), BrowserViewerAssets.ORIGIN)
        assertEquals("export const HTTPS_HOSTNAME = '${BrowserHttpsPolicy.HOSTNAME}';\n",
            String(assets().resource("/config.mjs")!!.bytes, Charsets.US_ASCII))
        assertTrue(BrowserViewerAssets.CSP.contains("wss://${BrowserViewerAssets.AUTHORITY};"))
    }

    @Test fun canonicalAssetsAndVersionQueriesHaveCorrectMime() {
        val assets = assets()
        assertEquals("text/html; charset=utf-8", assets.resource("/")!!.mime)
        assertArrayEquals(assets.resource("/")!!.bytes, assets.resource("/index.html")!!.bytes)
        assertEquals("text/css; charset=utf-8", assets.resource("/viewer.css?v=connection-diag-v1")!!.mime)
        assertEquals("text/javascript; charset=utf-8", assets.resource("/viewer.mjs?v=video-v1")!!.mime)
        for (name in BrowserViewerAssets.NAMES) assertNotNull(assets.resource("/$name"))
    }

    @Test fun removedAudioAssetsAreUnavailableAndMediaPlaybackIsBlocked() {
        val assets = assets()
        assertNull(assets.resource("/audio.mjs"))
        assertNull(assets.resource("/audio-protocol.mjs"))
        assertTrue(BrowserViewerAssets.CSP.contains("media-src 'none';"))
    }

    @Test fun neverResolvesFilesystemOrEncodedPaths() {
        for (path in listOf("/../index.html", "/%2e%2e/index.html", "/%2findex.html", "/./viewer.mjs",
            "/tests/core.test.mjs", "/README.md", "/cert.pem", "//index.html", "/index.html/",
            "https://tesla.mark4z.asia:9999/", "/viewer.mjs?token=secret", "/viewer.mjs?v=a&b=c",
            "/viewer.mjs?v=", "/viewer.mjs?v=../../file", "/viewer.mjs#fragment", "/viewer.mjs\\x")) {
            assertNull(path, assets().resource(path))
        }
    }

    @Test fun resourcesAreLoadedOnlyFromBoundedApkPrefix() {
        val seen = mutableListOf<String>()
        BrowserViewerAssets.load { seen.add(it); ByteArrayInputStream(byteArrayOf(1)) }
        assertEquals(BrowserViewerAssets.NAMES.map { "browser-carplay/$it" }, seen)
        assertThrows(IllegalArgumentException::class.java) {
            BrowserViewerAssets.load { ByteArrayInputStream(ByteArray(BrowserViewerAssets.MAX_FILE_BYTES + 1)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserViewerAssets.load { ByteArrayInputStream(ByteArray(BrowserViewerAssets.MAX_FILE_BYTES)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserViewerAssets.load { ByteArrayInputStream(ByteArray(0)) }
        }
    }

    @Test fun getHeadAndSameOriginNavigateWithoutApproval() {
        assertTrue(classify(request("/")) is BrowserLanProtocol.HttpRequestKind.Asset)
        assertTrue((classify(request("/viewer.mjs", "HEAD")) as BrowserLanProtocol.HttpRequestKind.Asset).headOnly)
        assertTrue(classify(request("/", origin = BrowserViewerAssets.ORIGIN)) is BrowserLanProtocol.HttpRequestKind.Asset)
        assertTrue(classify(request("/health")) is BrowserLanProtocol.HttpRequestKind.Health)
    }

    @Test fun staticRequestsRejectHostOriginMethodsBodiesAndUpgrades() {
        val invalid = listOf(request("/", host = "100.99.9.9:9999"), request("/", host = "tesla.mark4z.asia"),
            request("/", origin = "null"), request("/", origin = "https://mark4z.github.io"),
            request("/", method = "POST"), request("/", extra = mapOf("content-length" to "1")),
            request("/", extra = mapOf("transfer-encoding" to "chunked")),
            request("/", extra = mapOf("upgrade" to "websocket")),
            request("/", extra = mapOf("sec-websocket-key" to "test")))
        for (request in invalid) assertThrows(BrowserLanProtocol.Failure::class.java) { classify(request) }
    }

    @Test fun staticServingNeverChangesPlainLanPolicy() {
        assertThrows(BrowserLanProtocol.Failure::class.java) {
            BrowserLanProtocol.classifyRequest(request("/"), BrowserViewerAssets.AUTHORITY, BrowserViewerAssets.ORIGIN)
        }
        assertFalse(BrowserLanProtocol.isPrivateIpv4(java.net.InetAddress.getByName(BrowserViewerAssets.ADDRESS)))
    }

    @Test fun websocketRequiresExactSameOriginAndApprovalProtocolIsUnchanged() {
        val headers = mapOf("connection" to "Upgrade", "upgrade" to "websocket", "sec-websocket-version" to "13",
            "sec-websocket-key" to "dGhlIHNhbXBsZSBub25jZQ==")
        assertTrue(classify(request("/carplay", origin = BrowserViewerAssets.ORIGIN, extra = headers)) is BrowserLanProtocol.HttpRequestKind.Upgrade)
        for (origin in listOf(null, "https://mark4z.github.io", "https://tesla.mark4z.asia", "https://tesla.mark4z.asia:9999/")) {
            assertThrows(BrowserLanProtocol.Failure::class.java) { classify(request("/carplay", origin = origin, extra = headers)) }
        }
        assertTrue(BrowserLanProtocol.requestsApproval("{\"type\":\"requestApproval\",\"version\":2}".toByteArray()))
    }

    @Test fun headersDisallowRemoteCodeFramingAndMicrophone() {
        val resource = assets().resource("/")!!
        val response = String(BrowserViewerAssets.response(resource, false))
        val head = String(BrowserViewerAssets.response(resource, true))
        assertTrue(response.endsWith(String(resource.bytes)))
        assertTrue(head.endsWith("\r\n\r\n"))
        assertTrue(head.contains("Content-Length: ${resource.bytes.size}\r\n"))
        assertTrue(head.contains("Content-Security-Policy: ${BrowserViewerAssets.CSP}\r\n"))
        assertTrue(head.contains("Cross-Origin-Resource-Policy: same-origin\r\n"))
        assertTrue(head.contains("Permissions-Policy: microphone=(), camera=(), geolocation=()\r\n"))
        assertFalse(BrowserViewerAssets.CSP.contains("unsafe-"))
        assertFalse(BrowserViewerAssets.CSP.contains(" ws:"))
    }
}
