package com.shilapi.xcertplay.browser

import org.webrtc.PeerConnectionFactory

/** Socket policy for this app's explicitly started local HTTPS/VPN session only. */
internal object WebRtcAudioNetworkPolicy {
    fun factoryOptions(embeddedHttps: Boolean): PeerConnectionFactory.Options? {
        if (!embeddedHttps) return null // Preserve normal Android/VPN handling for the LAN viewer.
        return PeerConnectionFactory.Options().apply {
            // The Android monitor rejects hotspot interfaces without a ConnectivityManager
            // Network, and binds sockets on our synthetic TUN to its non-forwarding VPN.
            // Native interface enumeration includes the actual hotspot/LAN instead. This is
            // factory-local: it does not bind the process, modify routes, or stop the VPN.
            disableNetworkMonitor = true
            // This session's synthetic TUN cannot forward media. Exclude VPN adapters only
            // in this factory; let ICE consider every other adapter and address normally.
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_VPN
            // Network reachability is negotiated by ICE; DTLS encryption remains mandatory.
            disableEncryption = false
        }
    }
}

