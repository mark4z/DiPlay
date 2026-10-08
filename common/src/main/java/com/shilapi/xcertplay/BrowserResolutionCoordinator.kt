package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.shilapi.xcertplay.browser.BrowserResolutionPolicy
import com.shilapi.xcertplay.browser.BrowserResolutionReconnectGate
import org.json.JSONObject
import java.io.File

/** Main-thread owner of saved browser baseline and bounded automatic CarPlay renegotiation. */
class BrowserResolutionCoordinator(context: Context) {
    companion object {
        @Volatile private var instance: BrowserResolutionCoordinator? = null
        fun get(context: Context): BrowserResolutionCoordinator = instance ?: synchronized(this) {
            instance ?: BrowserResolutionCoordinator(context.applicationContext).also { instance = it }
        }
    }
    data class Evaluation(val effective: BrowserResolutionPolicy.Size,
                          val active: BrowserResolutionPolicy.Size?, val allowed: Boolean)

    private data class Pending(val id: Long, val baseline: BrowserResolutionPolicy.Size?,
                               val reply: (JSONObject) -> Unit, val isCurrent: () -> Boolean,
                               val resetRequested: Boolean = false)
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "browser-resolution.json"))
    private val handler = Handler(Looper.getMainLooper())
    private val gate = BrowserResolutionReconnectGate()
    private var owner: Any? = null
    private var evaluate: ((BrowserResolutionPolicy.Size?) -> Evaluation)? = null
    private var reconnect: (() -> Boolean)? = null
    private var pending: Pending? = null
    private var attempt: Pending? = null
    private var attemptEffective: BrowserResolutionPolicy.Size? = null
    private val poll = Runnable { evaluatePending() }
    private val timeout = Runnable { complete(false) }

    /** Corrupt/unbounded files fall back to normal Android display negotiation. */
    fun load(): BrowserResolutionPolicy.Size? = try {
        val json = file.openRead().use { input ->
            val bytes = ByteArray(1025)
            val count = input.read(bytes)
            require(count in 1..1024 && input.read() == -1)
            JSONObject(String(bytes, 0, count, Charsets.UTF_8))
        }
        if (!json.getBoolean("enabled")) null else {
            val width = numericDimension(json, "width")
            val height = numericDimension(json, "height")
            BrowserResolutionPolicy.normalize(width, height)?.takeIf {
                it.width.toDouble() == width && it.height.toDouble() == height
            }
        }
    } catch (_: Exception) { null }

    /** AtomicFile is deliberately outside cloud/Android backup. Null explicitly restores normal sizing. */
    fun save(size: BrowserResolutionPolicy.Size?): Boolean {
        if (size != null && BrowserResolutionPolicy.normalize(size.width.toDouble(), size.height.toDouble()) != size) return false
        val json = JSONObject().put("enabled", size != null)
        size?.let { json.put("width", it.width).put("height", it.height) }
        var output: java.io.FileOutputStream? = null
        return try {
            output = file.startWrite()
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            output = null
            load() == size
        } catch (_: Exception) {
            output?.let { file.failWrite(it) }
            false
        }
    }

    fun install(owner: Any, evaluate: (BrowserResolutionPolicy.Size?) -> Evaluation,
                reconnect: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        this.owner = owner
        this.evaluate = evaluate
        this.reconnect = reconnect
    }

    fun uninstall(owner: Any) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (this.owner !== owner) return
        this.owner = null
        evaluate = null
        reconnect = null
        pending = null
        attempt = null
        attemptEffective = null
        handler.removeCallbacks(poll)
        handler.removeCallbacks(timeout)
        if (gate.inFlight) gate.complete(false)
    }

    /** Called only by BrowserOutput's current authenticated connection handler. */
    fun request(json: JSONObject, reply: (JSONObject) -> Unit, isCurrent: () -> Boolean) {
        handler.post {
            if (!isCurrent()) return@post
            val id = try {
                val n = json.get("requestId") as? Number ?: throw IllegalArgumentException()
                require(n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble())
                n.toLong().also { require(it in 1..9_007_199_254_740_991L) }
            } catch (_: Exception) { return@post }
            val requested = try {
                val enabled = json.get("enabled") as? Boolean ?: throw IllegalArgumentException()
                if (!enabled) null else BrowserResolutionPolicy.normalize(
                    numericDimension(json, "width"), numericDimension(json, "height"))
                    ?: throw IllegalArgumentException()
            } catch (_: Exception) {
                respond(Pending(id, load(), reply, isCurrent), null, "nextConnection", "invalidDimensions")
                return@post
            }
            val next = Pending(id, requested, reply, isCurrent, requested == null || json.optBoolean("retry", false))
            if (!save(requested)) {
                respond(next, null, "nextConnection", "saveFailed")
                return@post
            }
            pending = next
            handler.removeCallbacks(poll)
            evaluatePending()
        }
    }

    private fun evaluatePending() {
        val next = pending ?: return
        if (!next.isCurrent()) { pending = null; return }
        val evaluation = try { evaluate?.invoke(next.baseline) } catch (_: Exception) { null }
        if (gate.inFlight) {
            respond(next, evaluation?.effective, "nextConnection")
            return // Retain resets/resizes through temporary teardown ineligibility.
        }
        if (next.resetRequested) {
            gate.reset() // Preserves the global cooldown, even for explicit retry.
            pending = next.copy(resetRequested = false)
        }
        if (evaluation == null || owner == null) {
            respond(next, null, "nextConnection")
            pending = null
            return
        }
        if (evaluation.active == evaluation.effective) {
            respond(next, evaluation.effective, "unchanged")
            pending = null
            return
        }
        if (evaluation.active == null || !evaluation.allowed || gate.failureBlocked) {
            respond(next, evaluation.effective, "nextConnection", if (gate.failureBlocked) "reconnectFailed" else null)
            pending = null
            return
        }
        if (!next.isCurrent()) { pending = null; return }
        if (gate.shouldReconnect(evaluation.effective, evaluation.active, SystemClock.elapsedRealtime())) {
            attempt = next
            attemptEffective = evaluation.effective
            pending = null
            respond(next, evaluation.effective, "reconnecting")
            handler.postDelayed(timeout, 30_000L)
            val started = try { reconnect?.invoke() == true } catch (_: Exception) { false }
            if (!started) complete(false)
        } else {
            if (gate.hasAttempted(evaluation.effective)) {
                respond(next, evaluation.effective, "nextConnection", "reconnectFailed")
                pending = null
            } else {
                respond(next, evaluation.effective, "nextConnection")
                handler.postDelayed(poll, BrowserResolutionReconnectGate.STABLE_MILLIS)
            }
        }
    }

    /** Host reports actual negotiated success or terminal failure, never merely a restart invocation. */
    fun complete(success: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { complete(success) }; return }
        if (!gate.inFlight) return
        handler.removeCallbacks(timeout)
        val current = attempt
        val effective = attemptEffective
        val verifiedSuccess = success && current != null && effective != null && try {
            evaluate?.invoke(current.baseline)?.active == effective
        } catch (_: Exception) { false }
        // A newer saved viewport can be read by startCarPlay during this attempt's teardown.
        // Treat that exact current pending target as success too, rather than opening a false circuit.
        val latest = pending?.takeIf { it.isCurrent() }
        val latestEvaluation = if (success && latest != null) try {
            evaluate?.invoke(latest.baseline)
        } catch (_: Exception) { null } else null
        val latestAchieved = latestEvaluation != null && latestEvaluation.active == latestEvaluation.effective
        gate.complete(verifiedSuccess || latestAchieved)
        if (latestAchieved && !verifiedSuccess) gate.reset() // A was superseded, not a failed A retry.
        attempt = null
        attemptEffective = null
        if (current != null) respond(current, effective, if (verifiedSuccess) "unchanged" else "nextConnection",
            if (verifiedSuccess || latestAchieved) null else "reconnectFailed")
        if (pending != null) {
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
    }

    private fun respond(request: Pending, effective: BrowserResolutionPolicy.Size?, applies: String, code: String? = null) {
        if (!request.isCurrent()) return
        val result = JSONObject().put("type", "browserResolution").put("requestId", request.id)
            .put("enabled", request.baseline != null).put("applies", applies)
        request.baseline?.let { result.put("width", it.width).put("height", it.height) }
        effective?.let { result.put("effectiveWidth", it.width).put("effectiveHeight", it.height) }
        code?.let { result.put("code", it) }
        try { request.reply(result) } catch (_: Exception) { /* A disconnected viewer cannot receive an ACK. */ }
    }

    private fun numericDimension(json: JSONObject, key: String): Double =
        (json.get(key) as? Number)?.toDouble() ?: throw IllegalArgumentException("Numeric dimension required")
}
