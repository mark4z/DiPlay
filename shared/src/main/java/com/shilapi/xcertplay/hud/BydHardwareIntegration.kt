package com.shilapi.xcertplay.hud

/**
 * The original Android host runs without BYD hardware integration. This is not a preference:
 * old saved vehicle options must never re-enable proprietary services or key interception.
 * Generic Android ADB, GPS, media keys and the virtual map stream remain available.
 */
object BydHardwareIntegration {
    const val ENABLED = false
}
