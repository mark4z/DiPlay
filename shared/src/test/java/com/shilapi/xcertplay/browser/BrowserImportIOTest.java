package com.shilapi.xcertplay.browser;

import org.junit.Test;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class BrowserImportIOTest {
    private static BrowserSession task() { return new BrowserSession(Runnable::run, () -> {}); }
    @Test public void readsAtLimitAndAlwaysClosesProvider() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream source = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public void close() { closed.set(true); }
        };
        assertArrayEquals(new byte[]{1, 2, 3}, BrowserImportIO.read(source, 3, task()));
        assertTrue(closed.get());
    }
    @Test public void oversizedReadIsBoundedAndCloses() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream source = new ByteArrayInputStream(new byte[100]) {
            @Override public void close() { closed.set(true); }
        };
        assertEquals("IMPORT_FILE_TOO_LARGE", assertThrows(IOException.class,
                () -> BrowserImportIO.read(source, 3, task())).getMessage());
        assertTrue(closed.get());
    }
    @Test public void cancelledBeforeLateOpenClosesWithoutRead() {
        BrowserSession task = task(); task.cancel();
        AtomicBoolean closed = new AtomicBoolean();
        InputStream source = new InputStream() {
            @Override public int read() { fail("must not read after cancellation"); return -1; }
            @Override public void close() { closed.set(true); }
        };
        assertThrows(IOException.class, () -> BrowserImportIO.read(source, 10, task));
        assertTrue(closed.get());
    }
    @Test public void cancellationDuringReadCannotReturnBytes() {
        BrowserSession task = task();
        InputStream source = new InputStream() {
            @Override public int read() { task.cancel(); return 1; }
        };
        assertThrows(IOException.class, () -> BrowserImportIO.read(source, 5, task));
    }
    @Test public void readFailureClosesAndCloseFailureIsRecorded() {
        BrowserSession task = task();
        InputStream source = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("provider failure"); }
            @Override public void close() throws IOException { throw new IOException("close failure"); }
        };
        assertThrows(IOException.class, () -> BrowserImportIO.read(source, 5, task));
        assertTrue(task.didCloseFail());
    }
}
