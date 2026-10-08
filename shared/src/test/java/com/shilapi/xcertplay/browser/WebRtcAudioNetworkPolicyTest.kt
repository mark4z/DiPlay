package com.shilapi.xcertplay.browser

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.PeerConnectionFactory

class WebRtcAudioNetworkPolicyTest {
    @Test fun ordinaryLanViewerKeepsDefaultNetworkAndVpnPolicy() {
        assertNull(WebRtcAudioNetworkPolicy.factoryOptions(false))
    }

    @Test fun embeddedHttpsUsesPhysicalInterfacesWithoutDisablingEncryption() {
        val options = requireNotNull(WebRtcAudioNetworkPolicy.factoryOptions(true))
        assertTrue(options.disableNetworkMonitor)
        assertFalse(options.disableEncryption)
        assertEquals(PeerConnectionFactory.Options.ADAPTER_TYPE_VPN or
            PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
            PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK, options.networkIgnoreMask)
        assertEquals(0, options.networkIgnoreMask and PeerConnectionFactory.Options.ADAPTER_TYPE_WIFI)
        assertEquals(0, options.networkIgnoreMask and PeerConnectionFactory.Options.ADAPTER_TYPE_ETHERNET)
    }

    @Test fun optionsAreOwnedByOnePeerFactory() {
        val first = requireNotNull(WebRtcAudioNetworkPolicy.factoryOptions(true))
        first.disableEncryption = true
        assertFalse(requireNotNull(WebRtcAudioNetworkPolicy.factoryOptions(true)).disableEncryption)
    }
}
