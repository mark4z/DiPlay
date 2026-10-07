package com.diplay.networkprobe;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProbePolicyTest {
    @Test public void permitsOnlyHighPortAndFormatsExactUrl() {
        assertEquals(18080, ProbePolicy.DEFAULT_PORT);
        assertEquals(9999, ProbePolicy.HTTPS_PORT);
        assertEquals("https://tesla.mark4z.asia:9999/health", ProbePolicy.healthUrl(9999));
        assertTrue(ProbePolicy.isTestPort(9999));
        assertEquals("http://100.99.9.9:18080/health", ProbePolicy.healthUrl(18080));
        assertEquals(5 * 60 * 1000L, ProbePolicy.DURATION_MS);
        for (int port : new int[]{-1, 0, 80, 81, 443, 8080, 65535}) {
            assertFalse(ProbePolicy.isTestPort(port));
            assertThrows(IllegalArgumentException.class, () -> ProbePolicy.healthUrl(port));
            assertThrows(IllegalArgumentException.class, () -> new ProbeSelfCheck(port));
        }
    }
    @Test public void modesHaveFixedIndependentAddressPlans() {
        assertArrayEquals(new String[]{"100.99.9.9"}, ProbePolicy.addresses(ProbePolicy.SINGLE));
        assertArrayEquals(new String[]{"100.99.9.9", "192.168.247.2"}, ProbePolicy.addresses(ProbePolicy.DUAL));
        assertFalse(ProbePolicy.isTestMode(0));
        assertFalse(ProbePolicy.isTestMode(3));
        assertThrows(IllegalArgumentException.class, () -> ProbePolicy.addresses(0));
        assertEquals(15_000L, ProbePolicy.STARTUP_TIMEOUT_MS);
    }
    @Test public void startGrantIsManualSingleUseAndNotPersisted() {
        ProbePolicy.StartGate gate = new ProbePolicy.StartGate();
        assertFalse(gate.hasPending());
        assertFalse(gate.consume(0));
        long first = gate.arm();
        assertTrue(gate.hasPending());
        gate.cancel();
        assertFalse(gate.hasPending());
        assertFalse(gate.consume(first));
        long second = gate.arm();
        assertFalse(gate.consume(first));
        assertTrue(gate.consume(second));
        assertFalse(gate.hasPending());
        assertFalse(gate.consume(second));
        assertFalse(new ProbePolicy.StartGate().consume(second));
    }
    @Test public void conflictsDetectHostAndCarrierRoutesButNotDefaultOrIpv6() {
        byte[] host = {100, 99, 9, 9};
        assertTrue(ProbePolicy.overlaps(host, host, 32));
        assertTrue(ProbePolicy.overlaps(host, new byte[]{100, 64, 0, 0}, 10));
        assertTrue(ProbePolicy.overlaps(host, new byte[]{100, 99, 9, 0}, 24));
        assertFalse(ProbePolicy.overlaps(host, new byte[]{100, 99, 10, 0}, 24));
        assertFalse(ProbePolicy.overlaps(host, new byte[]{0, 0, 0, 0}, 0));
        assertFalse(ProbePolicy.overlaps(host, new byte[16], 64));
        assertFalse(ProbePolicy.overlaps(host, host, 33));
        byte[] compatibility = {(byte) 192, (byte) 168, (byte) 247, 2};
        assertTrue(ProbePolicy.overlaps(compatibility, compatibility, 32));
        assertTrue(ProbePolicy.overlaps(compatibility, new byte[]{(byte) 192, (byte) 168, (byte) 247, 0}, 24));
        assertTrue(ProbePolicy.overlaps(compatibility, new byte[]{(byte) 192, (byte) 168, 0, 0}, 16));
        assertFalse(ProbePolicy.overlaps(compatibility, host, 32));
    }
}
