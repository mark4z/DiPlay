package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log

/** The relay owner stays alive in its own task; only the launcher represents it in Recents. */
internal object CarPlayTaskNavigation {
    fun homeIntent(context: Context, page: String = "home", backend: Boolean): Intent =
        Intent(context, DiPlayActivity::class.java).putExtra("page", page).addFlags(
            if (backend) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            else Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )

    fun notificationIntent(context: Context, backend: Boolean): Intent =
        if (backend) homeIntent(context, backend = true)
        else Intent(context, CarPlayHostActivity::class.java)

    fun showHome(activity: Activity, page: String, backend: Boolean) {
        // NEW_TASK selects the launcher's affinity instead of creating another home in the
        // .carplay task. CLEAR_TOP + SINGLE_TOP reuses its existing root and delivers the page.
        activity.startActivity(homeIntent(activity, page, backend))
        // Hide only after navigation succeeded. Do not finish/remove the host: its callbacks
        // still own forwarding, reconnects and the foreground session.
        if (backend) setHostExcludedFromRecents(activity, true)
    }

    fun setHostExcludedFromRecents(activity: Activity, excluded: Boolean) {
        try {
            val manager = activity.getSystemService(ActivityManager::class.java) ?: return
            // RecentTaskInfo.id is also available on our API 28 minimum.
            @Suppress("DEPRECATION")
            val ownTask = manager.appTasks.firstOrNull { it.taskInfo?.id == activity.taskId }
            ownTask?.setExcludeFromRecents(excluded)
        } catch (error: RuntimeException) {
            // OEM task-management failures must never disconnect a working relay.
            Log.w("CarPlayTaskNavigation", "Cannot update task visibility: ${error.javaClass.simpleName}")
        }
    }
}
