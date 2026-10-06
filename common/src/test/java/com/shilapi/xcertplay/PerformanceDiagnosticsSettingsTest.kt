package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.PerformanceDiagnostics
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class PerformanceDiagnosticsSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null

    @Before fun setUp() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        PerformanceDiagnostics.setEnabled(false)
        CarPlayBackgroundSession.clear()
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        PerformanceDiagnostics.setEnabled(false)
        CarPlayBackgroundSession.clear()
    }

    @Test fun freshAndExistingDebugLogPreferencesLeavePerformanceDiagnosticsOff() {
        assertFalse(AirPlayPersistence.loadPerformanceDiagnosticsEnabled(context))
        AirPlayPersistence.saveDebugLogsEnabled(context, true)
        assertFalse(AirPlayPersistence.loadPerformanceDiagnosticsEnabled(context))
        AirPlayPersistence.savePerformanceDiagnosticsEnabled(context, true)
        assertTrue(AirPlayPersistence.loadPerformanceDiagnosticsEnabled(context))
        assertTrue(AirPlayPersistence.loadDebugLogsEnabled(context))
        AirPlayPersistence.savePerformanceDiagnosticsEnabled(context, false)
        assertFalse(AirPlayPersistence.loadPerformanceDiagnosticsEnabled(context))
        assertTrue(AirPlayPersistence.loadDebugLogsEnabled(context))
    }

    @Test fun fullSettingsToggleSavesImmediatelyAndSurvivesActivityRecreation() {
        val activity = launchSettings()
        val toggle = performanceSwitch(activity)
        assertFalse(toggle.isChecked)
        assertFalse(PerformanceDiagnostics.enabled)
        toggle.performClick()
        assertTrue(AirPlayPersistence.loadPerformanceDiagnosticsEnabled(activity))
        assertTrue(PerformanceDiagnostics.enabled)
        assertFalse(AirPlayPersistence.loadDebugLogsEnabled(activity))
        controller!!.recreate()
        val recreated = controller!!.get()
        assertTrue(performanceSwitch(recreated).isChecked)
        assertTrue(PerformanceDiagnostics.enabled)
        performanceSwitch(recreated).performClick()
        assertFalse(AirPlayPersistence.loadPerformanceDiagnosticsEnabled(recreated))
        assertFalse(PerformanceDiagnostics.enabled)
    }

    @Test fun activityLoadRestoresSavedPerformanceDiagnostics() {
        AirPlayPersistence.savePerformanceDiagnosticsEnabled(context, true)
        val activity = launchSettings()
        assertTrue(performanceSwitch(activity).isChecked)
        assertTrue(PerformanceDiagnostics.enabled)
    }

    private fun launchSettings(): DiPlayActivity {
        val intent = Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent)
        controller!!.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        return controller!!.setup().get()
    }

    private fun performanceSwitch(activity: DiPlayActivity) = views(activity.window.decorView)
        .filterIsInstance<Switch>()
        .single { it.contentDescription == activity.getString(R.string.performance_diagnostics) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
