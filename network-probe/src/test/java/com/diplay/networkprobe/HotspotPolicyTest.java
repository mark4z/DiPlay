package com.diplay.networkprobe;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class HotspotPolicyTest {
    private byte[] ip(String text) throws Exception { return InetAddress.getByName(text).getAddress(); }

    @Test public void onlyPrivateIpv4IsEligible() throws Exception {
        for (String value : new String[]{"10.18.0.8", "10.0.0.1", "172.16.0.1", "172.31.255.1", "192.168.43.1"})
            assertTrue(HotspotPolicy.isPrivateIpv4(ip(value)));
        for (String value : new String[]{"0.0.0.0", "127.0.0.1", "169.254.1.1", "100.96.23.17", "172.15.0.1", "172.32.0.1", "8.8.8.8", "224.0.0.1", "::1"})
            assertFalse(HotspotPolicy.isPrivateIpv4(ip(value)));
        assertFalse(HotspotPolicy.isPrivateIpv4(null));
        assertFalse(HotspotPolicy.isPrivateIpv4(new byte[0]));
    }

    @Test public void namesAreConservativeAndNeverCellularOrTunnel() {
        for (String name : new String[]{"wlan0", "wlan1", "ap0", "swlan0", "softap0", "wifi0"})
            assertTrue(HotspotPolicy.isWifiApName(name));
        for (String name : new String[]{null, "rmnet0", "rmnet_data0", "ccmni0", "tun0", "eth0", "lo", "p2p0", "wlan0:1", "wlan", "ap0evil"})
            assertFalse(HotspotPolicy.isWifiApName(name));
    }

    @Test public void rejectsManagedDownVirtualPointToPointOrUnusableInterfaces() throws Exception {
        byte[] address = ip("10.18.0.8");
        assertTrue(HotspotPolicy.eligible("wlan0", address, 24, true, false, false, false, true, false));
        assertFalse(HotspotPolicy.eligible("wlan0", address, 24, true, false, false, false, true, true));
        assertFalse(HotspotPolicy.eligible("wlan0", address, 24, false, false, false, false, true, false));
        assertFalse(HotspotPolicy.eligible("wlan0", address, 24, true, true, false, false, true, false));
        assertFalse(HotspotPolicy.eligible("wlan0", address, 24, true, false, true, false, true, false));
        assertFalse(HotspotPolicy.eligible("wlan0", address, 24, true, false, false, true, true, false));
        assertFalse(HotspotPolicy.eligible("wlan0", address, 24, true, false, false, false, false, false));
        for (int prefix : new int[]{-1, 0, 31, 32, 33})
            assertFalse(HotspotPolicy.eligible("wlan0", address, prefix, true, false, false, false, true, false));
        for (String unusable : new String[]{"10.18.0.0", "10.18.0.255"})
            assertFalse(HotspotPolicy.eligible("wlan0", ip(unusable), 24, true, false, false, false, true, false));
    }

    @Test public void httpsUsesOnlyApprovedHostnameAndHttpUsesSelectedAddress() throws Exception {
        InetAddress address = InetAddress.getByAddress(ip("10.18.0.8"));
        assertEquals("https://test.mark4z.asia:9999/health", HotspotPolicy.healthUrl(address, 9999));
        assertEquals("http://10.18.0.8:18080/health", HotspotPolicy.healthUrl(address, 18080));
        assertThrows(IllegalArgumentException.class, () -> HotspotPolicy.healthUrl(address, 80));
        assertThrows(IllegalArgumentException.class, () -> HotspotPolicy.healthUrl(address, 443));
        assertThrows(IllegalArgumentException.class, () -> HotspotPolicy.healthUrl(null, 9999));
        assertThrows(IllegalArgumentException.class, () -> HotspotPolicy.healthUrl(InetAddress.getByName("0.0.0.0"), 9999));
    }
}
