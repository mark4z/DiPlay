package com.shilapi.xcertplay.browser;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserSessionGrantTest {
    @Test public void freshProcessAndWrongTokensNeverSupplyIdentity() {
        BrowserSessionGrant<Object> gate = new BrowserSessionGrant<>();
        assertNull(gate.consume(0));
        assertNull(gate.consume(1));
        assertFalse(gate.hasPending());
        assertThrows(IllegalArgumentException.class, () -> gate.arm(null));
    }
    @Test public void oneUseGrantIsBoundToExactIdentity() {
        BrowserSessionGrant<Object> gate = new BrowserSessionGrant<>();
        Object first = new Object(), second = new Object();
        long oldToken = gate.arm(first);
        long token = gate.arm(second);
        assertNull(gate.consume(oldToken));
        assertTrue(gate.hasPending());
        assertSame(second, gate.consume(token));
        assertFalse(gate.hasPending());
        assertNull(gate.consume(token));
    }
    @Test public void cancellationDropsIdentityAndRejectsLateStart() {
        BrowserSessionGrant<Object> gate = new BrowserSessionGrant<>();
        long token = gate.arm(new Object());
        gate.cancel();
        assertNull(gate.consume(token));
        assertFalse(gate.hasPending());
        Object next = new Object();
        assertSame(next, gate.consume(gate.arm(next)));
    }
}
