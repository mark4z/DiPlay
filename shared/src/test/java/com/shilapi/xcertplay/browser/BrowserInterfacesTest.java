package com.shilapi.xcertplay.browser;

import org.junit.Test;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class BrowserInterfacesTest {
    private static final class Lookup implements BrowserInterfaces.Lookup {
        final Map<String, BrowserInterfaces.Info> addresses = new HashMap<>(), names = new HashMap<>();
        boolean fail;
        @Override public BrowserInterfaces.Info byAddress(String address) throws Exception {
            if (fail) throw new IOException("unavailable");
            return addresses.get(address);
        }
        @Override public BrowserInterfaces.Info byName(String name) { return names.get(name); }
        void put(String address, String name, int index, boolean up) {
            BrowserInterfaces.Info info = new BrowserInterfaces.Info(name, index, up);
            addresses.put(address, info); names.put(name, info);
        }
    }
    @Test public void remembersOriginalIdentityAndReportsDownEvenIfAddressLookupLosesIt() {
        Lookup lookup = new Lookup();
        lookup.put(BrowserHttpsPolicy.ADDRESS, "tun7", 23, true);
        BrowserInterfaces observer = new BrowserInterfaces(BrowserHttpsPolicy.DUAL, lookup);
        assertTrue(observer.snapshot().text.contains("tun7 index=23 UP ADDRESS_PRESENT"));
        lookup.put(BrowserHttpsPolicy.ADDRESS, "tun7", 23, false);
        lookup.addresses.remove(BrowserHttpsPolicy.ADDRESS);
        lookup.put(BrowserHttpsPolicy.COMPATIBILITY_ADDRESS, "tun8", 24, true);
        String after = observer.snapshot().text;
        assertTrue(after.contains("tun7 index=23 DOWN ADDRESS_NOT_VISIBLE"));
        assertTrue(after.contains("tun8 index=24 UP ADDRESS_PRESENT"));
        lookup.addresses.clear(); lookup.names.clear();
        BrowserInterfaces.Snapshot closed = observer.snapshot();
        assertFalse(closed.visible);
        assertFalse(closed.failed);
        assertTrue(closed.text.contains("NOT_VISIBLE (original=tun7#23)"));
    }
    @Test public void reusedNameIsNotMisreportedAsOriginalInterface() {
        Lookup lookup = new Lookup();
        lookup.put(BrowserHttpsPolicy.ADDRESS, "tun2", 9, true);
        BrowserInterfaces observer = new BrowserInterfaces(BrowserHttpsPolicy.SINGLE, lookup);
        observer.snapshot();
        lookup.put(BrowserHttpsPolicy.ADDRESS, "tun2", 10, false);
        BrowserInterfaces.Snapshot result = observer.snapshot();
        assertTrue(result.visible);
        assertTrue(result.text.contains("IDENTITY_CHANGED"));
        assertFalse(result.text.contains(" DOWN"));
    }
    @Test public void failedObservationIsDistinctFromAbsenceOrDown() {
        Lookup lookup = new Lookup();
        lookup.fail = true;
        BrowserInterfaces.Snapshot result = new BrowserInterfaces(BrowserHttpsPolicy.DUAL, lookup).snapshot();
        assertTrue(result.failed);
        assertTrue(result.text.contains("READ_FAILED"));
        assertFalse(result.text.contains("NOT_VISIBLE"));
        assertFalse(result.text.contains(" DOWN"));
    }
}
