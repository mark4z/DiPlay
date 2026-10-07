package com.shilapi.xcertplay.browser;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserHttpsPolicyTest {
    @Test public void productionEndpointsAndDualOrderAreFixed() {
        assertEquals("https://tesla.mark4z.asia:9999/", BrowserHttpsPolicy.VIEWER_URL);
        assertEquals("wss://tesla.mark4z.asia:9999/carplay", BrowserHttpsPolicy.WEBSOCKET_URL);
        assertArrayEquals(new String[]{"100.99.9.9", "192.168.247.2"}, BrowserHttpsPolicy.addresses(BrowserHttpsPolicy.DUAL));
        assertThrows(IllegalArgumentException.class, () -> BrowserHttpsPolicy.addresses(0));
        String[] copy = BrowserHttpsPolicy.addresses(BrowserHttpsPolicy.DUAL);
        copy[0] = "0.0.0.0";
        assertEquals("100.99.9.9", BrowserHttpsPolicy.addresses(BrowserHttpsPolicy.DUAL)[0]);
    }
    @Test public void defaultRoutesAreNotConflictsButMatchingHostOrSubnetIs() {
        byte[] primary = {100, 99, 9, 9};
        assertFalse(BrowserHttpsPolicy.overlaps(primary, new byte[4], 0));
        assertTrue(BrowserHttpsPolicy.overlaps(primary, primary, 32));
        assertTrue(BrowserHttpsPolicy.overlaps(primary, new byte[]{100, 64, 0, 0}, 10));
        assertFalse(BrowserHttpsPolicy.overlaps(primary, new byte[]{100, 99, 9, 8}, 32));
        assertFalse(BrowserHttpsPolicy.overlaps(primary, new byte[16], 32));
    }
}
