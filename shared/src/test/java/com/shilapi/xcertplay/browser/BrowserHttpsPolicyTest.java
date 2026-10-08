package com.shilapi.xcertplay.browser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserHttpsPolicyTest {
    @Test public void productionEndpointsAndDualOrderAreFixed() {
        assertEquals("https://" + BrowserHttpsPolicy.HOSTNAME + ":9999/", BrowserHttpsPolicy.VIEWER_URL);
        assertEquals("wss://" + BrowserHttpsPolicy.HOSTNAME + ":9999/carplay", BrowserHttpsPolicy.WEBSOCKET_URL);
        assertArrayEquals(new String[]{"100.99.9.9", "192.168.247.2"}, BrowserHttpsPolicy.addresses(BrowserHttpsPolicy.DUAL));
        assertThrows(IllegalArgumentException.class, () -> BrowserHttpsPolicy.addresses(0));
        String[] copy = BrowserHttpsPolicy.addresses(BrowserHttpsPolicy.DUAL);
        copy[0] = "0.0.0.0";
        assertEquals("100.99.9.9", BrowserHttpsPolicy.addresses(BrowserHttpsPolicy.DUAL)[0]);
    }
    @Test public void generatedResourceIsPresentInAndroidJvmTests() throws Exception {
        try (java.io.InputStream resource = BrowserHttpsPolicy.class.getResourceAsStream(BrowserHttpsPolicy.CONFIG_RESOURCE)) {
            assertNotNull("Gradle must package the generated Java resource", resource);
            assertEquals(BrowserHttpsPolicy.HOSTNAME, BrowserHttpsPolicy.readHostname(resource));
        }
    }
    @Test public void optionalConfigAcceptsOnlyOneCanonicalHostname() throws Exception {
        assertEquals(BrowserHttpsPolicy.DEFAULT_HOSTNAME, BrowserHttpsPolicy.readHostname(null));
        assertEquals("viewer.example.com", BrowserHttpsPolicy.readHostname(input("hostname=viewer.example.com\n")));
        for (String value : new String[]{"", "viewer.example.com", "hostname=viewer.example.com", "hostname=Viewer.example.com\n",
            "hostname=viewer.example.com\nextra=true\n", "hostname=https://viewer.example.com\n", "hostname=127.0.0.1\n",
            "hostname=viewer.example.com:9999\n", "hostname=viewer.example.com\r\n", "hostname=é.example.com\n"}) {
            assertThrows(IOException.class, () -> BrowserHttpsPolicy.readHostname(input(value)));
        }
    }
    @Test public void hostnameNormalizationRejectsUrlAndInjectionForms() {
        assertEquals("viewer.example.com", BrowserHttpsPolicy.normalizeHostname(" Viewer.Example.COM "));
        for (String value : new String[]{"", "localhost", "127.0.0.1", "https://viewer.example.com", "viewer.example.com:9999",
            "viewer.example.com/", "viewer.example.com.", "*.example.com", "user@viewer.example.com", "viewer..example.com",
            "-viewer.example.com", "viewer_.example.com", "viewer.example.com\nextra=true", "é.example.com", "K.example.com",
            "a".repeat(64) + ".example.com", ("a".repeat(63) + ".").repeat(4) + "com"}) {
            assertThrows(IllegalArgumentException.class, () -> BrowserHttpsPolicy.normalizeHostname(value));
        }
    }
    @Test public void asciiCheckPrecedesUnicodeCaseFolding() {
        assertEquals("k.example.com", "K.example.com".toLowerCase(java.util.Locale.ROOT));
        assertThrows(IllegalArgumentException.class, () -> BrowserHttpsPolicy.normalizeHostname("K.example.com"));
        assertEquals("k.example.com", BrowserHttpsPolicy.normalizeHostname("K.example.com"));
    }
    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
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
