package com.shilapi.xcertplay.browser

/**
 * Queued input carries an ownership epoch. A transfer invalidates both input sources
 * immediately; only the touch executor may commit it after sending cancellation.
 */
internal class BrowserTouchOwnership {
    private var generation = 0L
    private var requestedBrowser = false
    private var ownedByBrowser = false
    private var transferring = false

    @Synchronized fun request(browser: Boolean): Long {
        requestedBrowser = browser
        transferring = true
        return ++generation
    }

    @Synchronized fun isCurrent(epoch: Long): Boolean = generation == epoch

    @Synchronized fun commit(epoch: Long): Boolean {
        if (generation != epoch) return false
        ownedByBrowser = requestedBrowser
        transferring = false
        return true
    }

    @Synchronized fun fail(epoch: Long) {
        if (generation != epoch) return
        ++generation
        requestedBrowser = false
        ownedByBrowser = false
        transferring = false
    }

    @Synchronized fun nativeEpoch(): Long? =
        generation.takeIf { !transferring && !requestedBrowser && !ownedByBrowser }

    @Synchronized fun browserEpoch(): Long? =
        generation.takeIf { !transferring && requestedBrowser && ownedByBrowser }

    @Synchronized fun acceptsNative(epoch: Long): Boolean =
        generation == epoch && !transferring && !requestedBrowser && !ownedByBrowser

    @Synchronized fun acceptsBrowser(epoch: Long): Boolean =
        generation == epoch && !transferring && requestedBrowser && ownedByBrowser
}

internal data class BrowserTouchTransfer(val epoch: Long, val enabled: Boolean, val completed: (Boolean) -> Unit)

/** One scheduled/in-flight worker and one replaceable transfer, even during a blocked HID write. */
internal class BrowserTouchTransfers {
    private var pending: BrowserTouchTransfer? = null
    private var scheduled = false

    @Synchronized fun offer(transfer: BrowserTouchTransfer): Boolean {
        pending = transfer
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized fun poll(): BrowserTouchTransfer? {
        val next = pending
        pending = null
        if (next == null) scheduled = false
        return next
    }

    @Synchronized fun clear(): BrowserTouchTransfer? {
        val next = pending
        pending = null
        scheduled = false
        return next
    }
}
