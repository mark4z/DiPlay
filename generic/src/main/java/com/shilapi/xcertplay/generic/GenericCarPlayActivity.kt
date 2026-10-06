package com.shilapi.xcertplay.generic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.util.concurrent.Executors

/**
 * Foreground-only generic host. No vendor services, hidden API grants, vehicle feeds or overlays.
 * All resources are released on Stop/onStop, and a user-started connection resumes on return.
 */
class GenericCarPlayActivity : Activity() {
    private lateinit var persistence: GenericPersistence
    private lateinit var settings: GenericSettings
    private lateinit var video: TextureView
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var idle: TextView
    private val main = Handler(Looper.getMainLooper())
    // A recreated Activity queues preparation behind the preceding Activity's full teardown.
    private val worker = GenericSessionLifecycle.worker
    private val retries = GenericReconnectBudget()
    @Volatile private var generation = 0
    private var foreground = false
    private var destroyed = false
    private var desired = false
    private var preparing = false
    private var closing = false
    private var awaitingPermission = false
    private var awaitingVpn = false
    private var retryPending = false
    private var microphonePrompted = false
    private var touchDown = false
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var settingsDialog: AlertDialog? = null
    private var surface: Surface? = null
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var mediaSession: GenericMediaSession? = null
    private var activeSession: AirPlaySession? = null
    private var canvas = 1280 to 720
    private var stableSession: Runnable? = null
    private val retry = Runnable { retryPending = false; maybeStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        persistence = GenericPersistence(applicationContext)
        settings = persistence.loadSettings()
        // A normal launch/USB attachment never silently starts a new connection.
        desired = savedInstanceState?.getBoolean("user_started", false) ?: false
        microphonePrompted = savedInstanceState?.getBoolean("microphone_prompted", false) ?: false
        awaitingPermission = savedInstanceState?.getBoolean("awaiting_permission", false) ?: false
        awaitingVpn = savedInstanceState?.getBoolean("awaiting_vpn", false) ?: false
        buildUi()
        refreshButtons()
    }

    private fun buildUi() {
        val padding = (8 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 20, 24))
            fitsSystemWindows = true
            setPadding(padding, padding, padding, padding)
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(title: String, action: () -> Unit) = Button(this).apply {
            text = title
            setOnClickListener { action() }
            toolbar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        startButton = button("Start") {
            if (desired) {
                desired = false
                cancelRetry()
                closeConnection()
                showStatus("Disconnected")
            } else {
                desired = true
                retries.reset()
                // Explicit Start can ask again after the user changes Android app permissions.
                microphonePrompted = false
                requestPrerequisites()
            }
            refreshButtons()
        }
        button("Reconnect") {
            desired = true
            retries.reset()
            cancelRetry()
            closeConnection()
            requestPrerequisites()
            refreshButtons()
        }
        button("Settings") { showSettings() }
        root.addView(toolbar)
        val frame = FrameLayout(this)
        video = TextureView(this).apply {
            isFocusable = true; isFocusableInTouchMode = true
            contentDescription = "CarPlay screen. Menu key opens connection settings."
            surfaceTextureListener = textureListener
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        frame.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        idle = TextView(this).apply {
            text = "DiPlay Generic\n\nChoose connection settings, then Start.\nKeep the app visible while connected.\n\nFor wireless, pair the iPhone using Android Bluetooth settings first.\n\nSource-only builds do not contain an accessory identity.\n\nHardware Menu opens settings."
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(padding * 2, padding, padding * 2, padding)
            textSize = 18f
        }
        frame.addView(idle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "Ready"; setTextColor(Color.LTGRAY); setPadding(padding, padding, padding, padding)
            textSize = 14f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (desired) maybeStart()
    }

    override fun onStop() {
        foreground = false
        cancelRetry()
        closeConnection()
        if (desired) showStatus("Connection paused while the app is hidden")
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        runCatching { settingsDialog?.dismiss() }
        settingsDialog = null
        closeConnection()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("user_started", desired)
        outState.putBoolean("microphone_prompted", microphonePrompted)
        outState.putBoolean("awaiting_permission", awaitingPermission)
        outState.putBoolean("awaiting_vpn", awaitingVpn)
        super.onSaveInstanceState(outState)
    }

    private fun showSettings() {
        if (settingsDialog != null || destroyed) return
        releaseInput()
        settingsDialog = GenericSettingsDialog.show(this, settings, { next ->
            persistence.saveSettings(next)
            settings = next
            microphonePrompted = false
            cancelRetry()
            retries.reset()
            closeConnection()
            if (desired) requestPrerequisites()
        }, { settingsDialog = null; if (activeSession != null) video.requestFocus() })
    }

    private fun missingRequiredPermissions(): List<String> = GenericPermissions.required(settings, Build.VERSION.SDK_INT)
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun requestPrerequisites() {
        if (!desired || !foreground || destroyed || awaitingPermission || awaitingVpn) return
        settings.validationError()?.let { failUntilUserActs(it); return }
        val missing = missingRequiredPermissions().toMutableList()
        if (settings.microphone && !microphonePrompted && !microphoneGranted()) {
            missing.add(Manifest.permission.RECORD_AUDIO)
            microphonePrompted = true
        }
        if (missing.isNotEmpty()) {
            awaitingPermission = true
            requestPermissions(missing.toTypedArray(), PERMISSIONS)
            return
        }
        requestVpnOrStart()
    }

    private fun requestVpnOrStart() {
        if (!desired || !foreground || destroyed) return
        if (!settings.wireless) {
            val consent = runCatching { VpnService.prepare(this) }.getOrElse {
                failUntilUserActs("VPN setup is unavailable on this device."); return
            }
            if (consent != null) {
                awaitingVpn = true
                @Suppress("DEPRECATION")
                try { startActivityForResult(consent, VPN_CONSENT) }
                catch (_: RuntimeException) { awaitingVpn = false; failUntilUserActs("Android could not open VPN consent.") }
                return
            }
        }
        maybeStart()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS) return
        awaitingPermission = false
        if (missingRequiredPermissions().isNotEmpty()) {
            failUntilUserActs("Required network/Bluetooth permission was denied. Grant it in Android app settings, then tap Start.")
        } else {
            if (settings.microphone && !microphoneGranted()) showStatus("Microphone denied; connecting without Siri/call microphone input")
            requestVpnOrStart()
        }
    }

    @Deprecated("Framework compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT) return
        awaitingVpn = false
        if (resultCode == RESULT_OK) maybeStart()
        else failUntilUserActs("VPN consent was denied. Tap Start to try again.")
    }

    private fun microphoneGranted(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun maybeStart() {
        if (!desired || !foreground || destroyed || preparing || closing || controller != null ||
            awaitingPermission || awaitingVpn || retryPending || surface == null || video.width <= 0 || video.height <= 0) return
        if (GenericSessionLifecycle.cleanupFailed) { failUntilUserActs("The previous session has not released its resources. Force stop this app in Android settings, then reopen."); return }
        if (missingRequiredPermissions().isNotEmpty()) {
            failUntilUserActs("Connection permission is missing. Tap Start to request it.")
            return
        }
        if (!settings.wireless && VpnService.prepare(this) != null) {
            // Restoring/resuming never silently reopens a consent prompt.
            failUntilUserActs("VPN consent is needed. Tap Start to continue.")
            return
        }
        val config = settings
        config.validationError()?.let { failUntilUserActs(it); return }
        val token = ++generation
        preparing = true
        showStatus("Preparing authentication")
        worker.execute {
            val result = runCatching {
                check(!GenericSessionLifecycle.cleanupFailed) { "Previous teardown is incomplete" }
                if (config.mfiTarget == MfiTarget.LOCAL) GenericAuthentication.ensureLocal(applicationContext)
                persistence.identity()
            }
            main.post {
                if (token != generation || destroyed) return@post
                preparing = false
                result.fold(onSuccess = { identity ->
                    if (!desired || !foreground) return@fold
                    try {
                        val airPlay = config.airPlay(identity, video.width, video.height, microphoneGranted(), GenericCapabilities.knobPrimary(this))
                        canvas = airPlay.main.widthPixels to airPlay.main.heightPixels
                        val renderer = AndroidMediaSink(
                            surface = surface, videoWidth = canvas.first, videoHeight = canvas.second,
                            context = applicationContext, audioFocusEnabled = true,
                            onScreenStreamActiveChanged = { type, active ->
                                if (type == MAIN_SCREEN) main.post {
                                    if (token == generation) idle.visibility = if (active) View.GONE else View.VISIBLE
                                }
                            },
                            onMediaAudioChanged = { active -> main.post { if (token == generation) mediaSession?.onPlaying(active) } },
                        )
                        sink = renderer
                        val next = CarPlayController(
                            context = applicationContext, config = config.runtime(identity), airPlayConfig = airPlay,
                            identity = identity, pairings = persistence.pairings(), listener = listener(token),
                            media = CarPlayMediaEngine(renderer, microphoneEnabled = airPlay.microphone),
                            reportStatus = { nextStatus -> if (token == generation) reportStatus(nextStatus) },
                            loadPairRecord = persistence::loadLockdown,
                            savePairRecord = persistence::saveLockdown,
                            clearPairRecord = persistence::clearLockdown,
                        )
                        controller = next
                        mediaSession = GenericMediaSession(this, next)
                        updateVideoLayout()
                        refreshButtons()
                        showStatus(if (airPlay.microphone) "Connecting" else "Connecting (microphone off)")
                        next.start()
                    } catch (_: Exception) {
                        failUntilUserActs("Connection could not start. Check authentication, USB/network availability and Android permissions.")
                    }
                }, onFailure = {
                    // Do not surface raw exception text that could contain local paths or credentials.
                    failUntilUserActs(if (config.mfiTarget == MfiTarget.LOCAL)
                        "Local authentication is missing or invalid. Source builds are identity-free; use explicitly provisioned assets or configure your authentication hardware/server."
                        else "Could not prepare the accessory identity.")
                })
            }
        }
    }

    private fun listener(token: Int): AirPlaySessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            main.post {
                if (token != generation) return@post
                activeSession = session
                mediaSession?.connected()
                registerCarPlayBack()
                video.requestFocus()
                showStatus("CarPlay connected · Menu opens settings")
            }
        }

        override fun onVideoFrameRendered(session: AirPlaySession) {
            main.post {
                if (token != generation || activeSession !== session || stableSession != null) return@post
                stableSession = Runnable {
                    if (token == generation && activeSession === session) retries.reset()
                }.also { main.postDelayed(it, 30000) }
            }
        }

        override fun onSessionEnded(session: AirPlaySession) {
            main.post {
                if (token != generation || (activeSession != null && activeSession !== session)) return@post
                reconnectAfterLoss("CarPlay session ended")
            }
        }

        override fun onTransportError(message: String) {
            main.post { if (token == generation) reconnectAfterLoss("Connection interrupted") }
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            main.post { if (token == generation) showSettings() }
        }
    }

    private fun reportStatus(value: CarPlayStatus) {
        when (value) {
            is CarPlayStatus.Failed -> {
                // Authentication/permission/startup failures need user intervention, not an endless restart.
                failUntilUserActs(if (value.wifiResetRequired)
                    "Wireless setup failed. Check Android Wi-Fi/Bluetooth settings, then tap Start."
                    else "Connection failed. Check authentication and the selected USB/network settings, then tap Start.")
            }
            CarPlayStatus.ControlEnded -> reconnectAfterLoss("Phone control link ended")
            CarPlayStatus.WaitingForIphone -> showStatus("Waiting for iPhone USB. Connect and unlock it.")
            CarPlayStatus.RequestingIphonePermission -> showStatus("Allow Android USB access to the iPhone")
            CarPlayStatus.WaitingForMfi -> showStatus("Waiting for the configured authentication hardware")
            CarPlayStatus.RequestingMfiPermission -> showStatus("Allow Android USB access to the authentication device")
            CarPlayStatus.WaitingForPairedIphone -> showStatus("Waiting for the paired iPhone. Check Android Bluetooth settings.")
            CarPlayStatus.StartingHotspot -> showStatus("Preparing the selected wireless network")
            is CarPlayStatus.HotspotReady -> showStatus("Wireless network ready; connecting to the paired iPhone")
            CarPlayStatus.ConnectingBluetooth -> showStatus("Connecting to the paired iPhone over Bluetooth")
            CarPlayStatus.Pairing -> showStatus("Pairing with iPhone. Unlock it and approve Trust if asked.")
            CarPlayStatus.AttachingNetwork -> showStatus("Opening the wired CarPlay network")
            CarPlayStatus.RunningControl, CarPlayStatus.RunningWireless -> showStatus("Waiting for the CarPlay screen")
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> showStatus("Wireless CarPlay active")
            else -> if (activeSession == null) showStatus("Connecting…")
        }
    }

    private fun reconnectAfterLoss(reason: String) {
        if (!desired || !foreground || retryPending || destroyed) return
        val delay = retries.nextDelayMillis()
        if (delay == null) { failUntilUserActs("Connection keeps dropping. Check the phone and network, then tap Start."); return }
        retryPending = true
        closeConnection()
        showStatus("$reason. Reconnecting in ${delay / 1000} seconds…")
        main.postDelayed(retry, delay)
    }

    private fun cancelRetry() {
        main.removeCallbacks(retry)
        retryPending = false
    }

    private fun failUntilUserActs(message: String) {
        desired = false
        cancelRetry()
        closeConnection()
        showStatus(message)
        refreshButtons()
    }

    private fun closeConnection() {
        generation++
        preparing = false
        stableSession?.let(main::removeCallbacks)
        stableSession = null
        val inputReleased = runCatching { releaseInput() }.isSuccess
        val previous = controller
        val renderer = sink
        val previousMedia = mediaSession
        val previousBack = backCallback
        // Detach before TextureView can be destroyed while controller teardown is still waiting.
        val surfaceDetached = runCatching { surface?.let { renderer?.clearSurface(MAIN_SCREEN, it) } }.isSuccess
        controller = null; sink = null; activeSession = null
        mediaSession = null
        backCallback = null
        val mediaClosed = runCatching { previousMedia?.close() }.isSuccess
        val backRemoved = runCatching {
            if (Build.VERSION.SDK_INT >= 33) previousBack?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }.isSuccess
        if (::idle.isInitialized) idle.visibility = View.VISIBLE
        if (previous == null && renderer == null) return
        closing = true
        val closeRequested = runCatching { previous?.close() }.isSuccess
        worker.execute {
            val complete = runCatching { previous?.awaitClosed(15000) ?: true }.getOrDefault(false)
            val rendererClosed = runCatching { renderer?.close() }.isSuccess
            GenericSessionLifecycle.cleanupFailed = GenericSessionLifecycle.cleanupFailed ||
                !inputReleased || !surfaceDetached || !mediaClosed || !backRemoved || !closeRequested || !complete || !rendererClosed
            main.post {
                closing = false
                if (!destroyed) {
                    if (GenericSessionLifecycle.cleanupFailed) failUntilUserActs("The old connection did not finish closing. Force stop this app in Android settings, then reopen.")
                    else maybeStart()
                }
            }
        }
    }

    private fun showStatus(message: String) { if (!destroyed) status.text = message }

    private fun refreshButtons() {
        if (!::startButton.isInitialized) return
        startButton.text = if (desired) "Stop" else "Start"
        if (desired) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releaseInput() {
        touchDown = false
        controller?.sendTouch(emptyList())
        controller?.sendKnob(AirPlayKnobState(), false)
    }

    private fun registerCarPlayBack() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) return
        val callback = android.window.OnBackInvokedCallback {
            val current = controller
            if (current != null && activeSession != null && settingsDialog == null && video.hasFocus()) {
                // Android 16+ routes system Back through this API instead of KEYCODE_BACK.
                current.sendKnob(AirPlayKnobState(back = true), momentary = true)
            } else finish()
        }
        backCallback = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
    }

    private fun contentRect(): CarPlayVideoLayout = CarPlayVideoLayout.fit(canvas.first, canvas.second, video.width, video.height)

    private fun handleTouch(event: MotionEvent): Boolean {
        val next = controller ?: return false
        if (activeSession == null || settingsDialog != null) return false
        val rect = contentRect()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!rect.contains(event.x, event.y)) return false
            touchDown = true
            video.requestFocus()
        }
        if (!touchDown) return false
        next.sendTouch(CarPlayTouchMapper.contacts(event, rect))
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touchDown = false
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showSettings()
            return true
        }
        val next = controller
        if (next != null && activeSession != null && settingsDialog == null && video.hasFocus() &&
            GenericInput.key(event, next::sendMediaButton, next::requestSiri, next::sendKnob)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val next = controller
        if (next != null && activeSession != null && settingsDialog == null && video.hasFocus() &&
            GenericInput.rotary(event, next::sendKnob)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun updateVideoLayout() {
        if (video.width <= 0 || video.height <= 0) return
        val rect = contentRect()
        video.setTransform(Matrix().apply {
            setScale(rect.width / video.width, rect.height / video.height)
            postTranslate(rect.left, rect.top)
        })
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            surface = Surface(texture).also { sink?.setSurface(MAIN_SCREEN, it) }
            updateVideoLayout()
            maybeStart()
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            // Keep the negotiated canvas stable across rotation/multi-window; input follows the same fit.
            releaseInput()
            updateVideoLayout()
            maybeStart()
        }
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            releaseInput()
            surface?.let { sink?.clearSurface(MAIN_SCREEN, it); it.release() }
            surface = null
            return true
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    companion object {
        private const val PERMISSIONS = 100
        private const val VPN_CONSENT = 101
        private const val MAIN_SCREEN = 110
    }
}

/** Serializes resource teardown and provisioning across framework Activity recreation. */
internal object GenericSessionLifecycle {
    val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "generic-session-lifecycle").apply { isDaemon = true }
    }
    @Volatile var cleanupFailed = false
}
