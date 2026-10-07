package com.diplay.networkprobe;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProbePolicyTest {
    @Test public void permitsOnlyTheTwoExplicitPortsAndFormatsExactUrls() {
        assertEquals(18080, ProbePolicy.DEFAULT_PORT);
        assertEquals("http://100.96.23.17/health", ProbePolicy.healthUrl(80));
        assertEquals("http://100.96.23.17:18080/health", ProbePolicy.healthUrl(18080));
        assertEquals(5 * 60 * 1000L, ProbePolicy.DURATION_MS);
        for (int port : new int[]{-1, 0, 81, 443, 8080, 65535}) {
            assertFalse(ProbePolicy.isTestPort(port));
            assertThrows(IllegalArgumentException.class, () -> ProbePolicy.healthUrl(port));
            assertThrows(IllegalArgumentException.class, () -> new ProbeSelfCheck(port));
        }
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
        byte[] host = {100, 96, 23, 17};
        assertTrue(ProbePolicy.overlaps(host, host, 32));
        assertTrue(ProbePolicy.overlaps(host, new byte[]{100, 64, 0, 0}, 10));
        assertTrue(ProbePolicy.overlaps(host, new byte[]{100, 96, 23, 0}, 24));
        assertFalse(ProbePolicy.overlaps(host, new byte[]{100, 96, 24, 0}, 24));
        assertFalse(ProbePolicy.overlaps(host, new byte[]{0, 0, 0, 0}, 0));
        assertFalse(ProbePolicy.overlaps(host, new byte[16], 64));
        assertFalse(ProbePolicy.overlaps(host, host, 33));
    }
}
