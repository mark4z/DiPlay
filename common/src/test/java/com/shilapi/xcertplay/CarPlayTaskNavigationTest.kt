package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class CarPlayTaskNavigationTest {
    private lateinit var activity: Activity
    private lateinit var manager: ActivityManager
    private lateinit var hostTask: ActivityManager.AppTask
    private lateinit var launcherTask: ActivityManager.AppTask

    @Before fun setUp() {
        activity = mock(Activity::class.java)
        manager = mock(ActivityManager::class.java)
        hostTask = task(42)
        launcherTask = task(7)
        `when`(activity.packageName).thenReturn("com.shihab.diplay")
        `when`(activity.taskId).thenReturn(42)
        `when`(activity.getSystemService(ActivityManager::class.java)).thenReturn(manager)
        `when`(manager.appTasks).thenReturn(listOf(launcherTask, hostTask))
    }

    @Test fun backendReturnsToLauncherTaskWithoutDestroyingHost() {
        val intent = CarPlayTaskNavigation.homeIntent(activity, "settings", backend = true)
        assertEquals(DiPlayActivity::class.java.name, intent.component?.className)
        assertEquals("settings", intent.getStringExtra("page"))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)

        CarPlayTaskNavigation.showHome(activity, "settings", backend = true)
        val order = inOrder(activity, hostTask)
        order.verify(activity).startActivity(any(Intent::class.java))
        order.verify(hostTask).setExcludeFromRecents(true)
        verify(launcherTask, never()).setExcludeFromRecents(anyBoolean())
        verify(launcherTask, never()).finishAndRemoveTask()
        verify(activity, never()).finish()
        verify(activity, never()).finishAndRemoveTask()
        verify(hostTask, never()).finishAndRemoveTask()
    }

    @Test fun nativeHomeKeepsExistingNavigationAndRecents() {
        assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            CarPlayTaskNavigation.homeIntent(activity, backend = false).flags)
        CarPlayTaskNavigation.showHome(activity, "home", backend = false)
        verify(hostTask, never()).setExcludeFromRecents(anyBoolean())
    }

    @Test fun newNativeOrPermissionHostCanRestoreItsRecentsEntry() {
        CarPlayTaskNavigation.setHostExcludedFromRecents(activity, false)
        verify(hostTask).setExcludeFromRecents(false)
        verify(launcherTask, never()).setExcludeFromRecents(anyBoolean())
    }

    @Test fun failedHomeLaunchDoesNotHideOnlyReachableHost() {
        doThrow(ActivityNotFoundException()).`when`(activity).startActivity(any(Intent::class.java))
        try {
            CarPlayTaskNavigation.showHome(activity, "home", backend = true)
            fail("Expected launch failure")
        } catch (_: ActivityNotFoundException) { }
        verify(hostTask, never()).setExcludeFromRecents(anyBoolean())
    }

    @Test fun missingOrRejectedTaskVisibilityDoesNotInterruptSession() {
        `when`(manager.appTasks).thenReturn(listOf(launcherTask))
        CarPlayTaskNavigation.showHome(activity, "home", backend = true)
        verify(launcherTask, never()).setExcludeFromRecents(anyBoolean())
        `when`(manager.appTasks).thenReturn(listOf(hostTask))
        doThrow(SecurityException()).`when`(hostTask).setExcludeFromRecents(true)
        CarPlayTaskNavigation.showHome(activity, "home", backend = true)
        verify(activity, never()).finish()
        verify(hostTask, never()).finishAndRemoveTask()
    }

    @Test fun disappearingTaskDoesNotPreventFindingHost() {
        val disappeared = mock(ActivityManager.AppTask::class.java)
        `when`(disappeared.taskInfo).thenReturn(null)
        `when`(manager.appTasks).thenReturn(listOf(disappeared, launcherTask, hostTask))
        CarPlayTaskNavigation.showHome(activity, "home", backend = true)
        verify(hostTask).setExcludeFromRecents(true)
        verify(disappeared, never()).setExcludeFromRecents(anyBoolean())
        verify(launcherTask, never()).setExcludeFromRecents(anyBoolean())
    }

    @Test fun notificationOpensStatusForBackendAndProjectionForNative() {
        val backend = CarPlayTaskNavigation.notificationIntent(activity, backend = true)
        assertEquals(DiPlayActivity::class.java.name, backend.component?.className)
        assertEquals("home", backend.getStringExtra("page"))
        assertEquals(CarPlayTaskNavigation.homeIntent(activity, backend = true).flags, backend.flags)
        val native = CarPlayTaskNavigation.notificationIntent(activity, backend = false)
        assertEquals(CarPlayHostActivity::class.java.name, native.component?.className)
        assertEquals(0, native.flags)
    }

    @Suppress("DEPRECATION")
    private fun task(id: Int): ActivityManager.AppTask = mock(ActivityManager.AppTask::class.java).also {
        `when`(it.taskInfo).thenReturn(ActivityManager.RecentTaskInfo().apply { this.id = id })
    }

}
