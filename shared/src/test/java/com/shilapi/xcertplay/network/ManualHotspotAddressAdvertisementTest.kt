package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in AP now uses the same dual-stack address policy as Same LAN. */
class ManualHotspotAddressAdvertisementTest {
    private val ipv4 = InetAddress.getByName("192.168.43.1")
    private val ipv6 = InetAddress.getByName("fe80::1234") as Inet6Address

    @Test
    fun discoveryAddsScopedIpv6WithoutReplacingTheIpv4Primary() {
        val addresses = listOf(ipv6, ipv4)
        val advertised = existingWifiHostAddresses(addresses, 7)

        assertEquals(ipv4, wirelessHostAddress(addresses, 7))
        assertEquals(2, advertised.size)
        assertEquals(ipv4, advertised.first())
        assertTrue(advertised.last() is Inet6Address)
        assertEquals(7, (advertised.last() as Inet6Address).scopeId)
        assertEquals(ipv6, advertised.last())
    }

    @Test
    fun discoveryNeverPublishesAnUnscopedIpv6Address() {
        assertEquals(listOf(ipv4), existingWifiHostAddresses(listOf(ipv6, ipv4), 0))
        assertTrue(existingWifiHostAddresses(listOf(ipv6), 0).isEmpty())
    }

    @Test
    fun discoveryKeepsSingleFamilyHotspotsUsable() {
        assertEquals(listOf(ipv4), existingWifiHostAddresses(listOf(ipv4), 7))
        val onlyIpv6 = existingWifiHostAddresses(listOf(ipv6), 7).single() as Inet6Address
        assertEquals(ipv6, onlyIpv6)
        assertEquals(7, onlyIpv6.scopeId)
    }
}
