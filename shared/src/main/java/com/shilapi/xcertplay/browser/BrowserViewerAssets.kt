package com.shilapi.xcertplay.browser

import java.io.InputStream

/** Immutable, bounded APK resources. Request paths are allowlisted, never filesystem paths. */
class BrowserViewerAssets private constructor(private val files: Map<String, ByteArray>) {
    data class Resource(val mime: String, val bytes: ByteArray)

    fun resource(target: String): Resource? {
        val match = Regex("(/[^?]*)(?:\\?v=[A-Za-z0-9.-]{1,64})?").matchEntire(target) ?: return null
        val path = match.groupValues[1].let { if (it == "/") "/index.html" else it }
        val bytes = files[path] ?: return null
        return Resource(mime(path), bytes)
    }

    companion object {
        val HOSTNAME = BrowserHttpsPolicy.HOSTNAME
        const val PORT = BrowserHttpsPolicy.HTTPS_PORT
        val AUTHORITY = "$HOSTNAME:$PORT"
        val ORIGIN = "https://$AUTHORITY"
        const val ADDRESS = BrowserHttpsPolicy.ADDRESS
        const val COMPAT_ADDRESS = BrowserHttpsPolicy.COMPATIBILITY_ADDRESS
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_TOTAL_BYTES = 2 * 1024 * 1024
        val NAMES = listOf("index.html", "viewer.css", "viewer.mjs", "core.mjs",
            "session.mjs", "controls.mjs", "resolution.mjs", "audio.mjs", "audio-protocol.mjs", "diagnostics.mjs", "config.mjs")
        val CSP = "default-src 'none'; script-src 'self'; style-src 'self'; " +
            "connect-src 'self' wss://$AUTHORITY; worker-src 'self'; " +
            "img-src 'self'; media-src 'self' blob:; base-uri 'none'; form-action 'none'; " +
            "object-src 'none'; frame-ancestors 'none'"

        fun load(open: (String) -> InputStream): BrowserViewerAssets {
            var total = 0
            val files = NAMES.associate { name ->
                val bytes = open("browser-carplay/$name").use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        require(out.size() + count <= MAX_FILE_BYTES) { "Viewer asset exceeds limit" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                require(bytes.isNotEmpty()) { "Viewer asset is empty" }
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "Viewer assets exceed limit" }
                // The installed APK policy is authoritative. A static-site default
                // cannot diverge from TLS validation, request routing or the UI.
                "/$name" to if (name == "config.mjs") configurationModule() else bytes
            }
            return BrowserViewerAssets(files)
        }

        internal fun configurationModule(): ByteArray =
            "export const HTTPS_HOSTNAME = '$HOSTNAME';\n".toByteArray(Charsets.US_ASCII)

        private fun mime(path: String): String = when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".mjs") -> "text/javascript; charset=utf-8"
            else -> error("Unlisted asset type")
        }

        fun response(resource: Resource, headOnly: Boolean): ByteArray {
            val headers = "HTTP/1.1 200 OK\r\nContent-Type: ${resource.mime}\r\n" +
                "Content-Length: ${resource.bytes.size}\r\nConnection: close\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
                "Referrer-Policy: no-referrer\r\nCross-Origin-Resource-Policy: same-origin\r\n" +
                "Permissions-Policy: microphone=(), camera=(), geolocation=()\r\n" +
                "Content-Security-Policy: $CSP\r\n\r\n"
            return headers.toByteArray(Charsets.US_ASCII) + if (headOnly) ByteArray(0) else resource.bytes
        }
    }
}
