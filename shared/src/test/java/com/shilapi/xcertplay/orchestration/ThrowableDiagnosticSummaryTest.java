package com.shilapi.xcertplay.orchestration;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThrowableDiagnosticSummaryTest {
    @Test public void describesClassesAndFirstSourceFramesWithoutAnyMessages() {
        IOException cause = new IOException("unlabelled-synthetic-secret-8f7g");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("example.UsbRequest", "queue", "UsbRequest.java", 123),
                new StackTraceElement("example.HiddenFrame", "run", "HiddenFrame.java", 12)
        });
        IllegalStateException error = new IllegalStateException("outer-synthetic-secret", cause);
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("example.Iap2UsbSession", "read", "IphoneUsbHost.kt", 400)
        });
        String result = ThrowableDiagnosticSummary.describe(error);
        assertEquals("IllegalStateException @ Iap2UsbSession.read(IphoneUsbHost.kt:400)"
                + " <- IOException @ UsbRequest.queue(UsbRequest.java:123)", result);
        assertFalse(result.contains("synthetic-secret"));
        assertFalse(result.contains("HiddenFrame"));
    }

    @Test public void neverReadsOverriddenMessageAccessors() {
        Throwable error = new RuntimeException() {
            @Override public String getMessage() { throw new AssertionError("message was read"); }
            @Override public String getLocalizedMessage() { throw new AssertionError("message was read"); }
            @Override public String toString() { throw new AssertionError("string was read"); }
        };
        assertNotNull(ThrowableDiagnosticSummary.describe(error));
    }

    @Test public void cyclicCausesTerminateByIdentity() {
        RuntimeException first = new RuntimeException("first-hidden");
        RuntimeException second = new RuntimeException("second-hidden");
        first.setStackTrace(new StackTraceElement[0]);
        second.setStackTrace(new StackTraceElement[0]);
        first.initCause(second);
        second.initCause(first);
        assertEquals("RuntimeException <- RuntimeException <- <cycle>",
                ThrowableDiagnosticSummary.describe(first));
    }

    @Test public void veryDeepCausesStopAtFiveAndRemainBounded() {
        Throwable error = null;
        for (int i = 0; i < 10_000; i++) {
            error = new RuntimeException("hidden-" + i, error);
            error.setStackTrace(new StackTraceElement[0]);
        }
        String result = ThrowableDiagnosticSummary.describe(error);
        assertEquals(5, result.split("RuntimeException", -1).length - 1);
        assertTrue(result.endsWith("<truncated>"));
        assertTrue(result.length() <= 650);
    }

    @Test public void hugeFrameFieldsAreSanitizedBoundedAndPathsAreRemoved() {
        Throwable error = null;
        for (int i = 0; i < 5; i++) {
            error = new RuntimeException("hidden", error);
            error.setStackTrace(new StackTraceElement[] {
                    new StackTraceElement("Owner".repeat(1000), "method\n".repeat(1000),
                            "/private/local/path/Source\r.java".repeat(1000), 99)
            });
        }
        String result = ThrowableDiagnosticSummary.describe(error);
        assertTrue(result.length() <= 650);
        assertFalse(result.contains("\n"));
        assertFalse(result.contains("\r"));
        assertFalse(result.contains("/private/"));
    }

    @Test public void nullAndMissingSourceAreSafe() {
        assertEquals("", ThrowableDiagnosticSummary.describe(null));
        Throwable error = new Throwable();
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("example.Unknown", "run", null, -1)
        });
        assertEquals("Throwable @ Unknown.run(?:-1)", ThrowableDiagnosticSummary.describe(error));
    }
}
