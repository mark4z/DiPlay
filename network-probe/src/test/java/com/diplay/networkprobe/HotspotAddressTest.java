package com.diplay.networkprobe;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class HotspotAddressTest {
    @Test public void revalidationRequiresExactNameIndexAddressAndPrefix() throws Exception {
        InetAddress ip = InetAddress.getByName("10.18.0.8");
        HotspotAddress selected = new HotspotAddress("wlan0", 7, ip, 24);
        assertTrue(selected.same(new HotspotAddress("wlan0", 7, ip, 24)));
        assertFalse(selected.same(new HotspotAddress("wlan1", 7, ip, 24)));
        assertFalse(selected.same(new HotspotAddress("wlan0", 8, ip, 24)));
        assertFalse(selected.same(new HotspotAddress("wlan0", 7, InetAddress.getByName("10.18.0.9"), 24)));
        assertFalse(selected.same(new HotspotAddress("wlan0", 7, ip, 16)));
        assertFalse(selected.same(null));
    }

    @Test public void labelsUseSelectedAddressButTlsUrlUsesExplicitHotspotHostname() throws Exception {
        HotspotAddress selected = new HotspotAddress("ap0", 7, InetAddress.getByName("10.18.0.8"), 24);
        assertEquals("10.18.0.8 /24 · ap0", selected.label());
        assertEquals("https://test.mark4z.asia:9999/health", selected.url(9999));
        assertEquals("http://10.18.0.8:18080/health", selected.url(18080));
    }
}
