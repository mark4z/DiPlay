package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserHttpsForegroundTest {
    private val first = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val second = Robolectric.buildActivity(Activity::class.java).setup().get()
    @After fun cleanup() {
        BrowserHttpsForeground.leave(first)
        BrowserHttpsForeground.leave(second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }
    @Test fun homeToCarPlayHandoffKeepsVisibilityAndDoesNotCreateAnotherAutostart() {
        BrowserHttpsForeground.enter(first)
        assertTrue(BrowserHttpsForeground.claimAutoStart())
        BrowserHttpsForeground.leave(first)
        BrowserHttpsForeground.enter(second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(BrowserHttpsForeground.visible)
        assertFalse(BrowserHttpsForeground.claimAutoStart())
    }
    @Test fun actualBackgroundEndsVisibilityAndNextOpenCanTryOnce() {
        BrowserHttpsForeground.enter(first)
        BrowserHttpsForeground.suppressAutoStart()
        BrowserHttpsForeground.leave(first)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(BrowserHttpsForeground.visible)
        assertFalse(BrowserHttpsForeground.claimAutoStart())
        BrowserHttpsForeground.enter(second)
        assertTrue(BrowserHttpsForeground.claimAutoStart())
        assertFalse(BrowserHttpsForeground.claimAutoStart())
    }
    @Test fun systemConsentDoesNotResetTheAttemptOrMakeDenialLoop() {
        BrowserHttpsForeground.enter(first)
        BrowserHttpsForeground.suppressAutoStart()
        BrowserHttpsForeground.leave(first, preserveAutoStart = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        BrowserHttpsForeground.enter(first)
        assertFalse(BrowserHttpsForeground.claimAutoStart())
    }
}
