package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView

/** Optional detail entry; the same controls are also directly on the DiPlay home screen. */
class BrowserHttpsActivity : Activity() {
    internal val controls by lazy { BrowserHttpsControls(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScrollView(this).apply { addView(controls.createView()) })
    }
    override fun onStart() { super.onStart(); BrowserHttpsForeground.enter(this) }
    override fun onResume() { super.onResume(); controls.onResume() }
    override fun onPause() { controls.onPause(); super.onPause() }
    override fun onStop() { controls.onStop(); BrowserHttpsForeground.leave(this, preserveAutoStart = controls.hasSystemInteraction); super.onStop() }
    override fun onDestroy() { controls.onDestroy(); super.onDestroy() }
    @Deprecated("Platform result forwarding for the local-only HTTPS picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        controls.onActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        controls.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
