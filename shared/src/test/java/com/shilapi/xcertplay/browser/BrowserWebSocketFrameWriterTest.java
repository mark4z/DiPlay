package com.shilapi.xcertplay.browser;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BrowserWebSocketFrameWriterTest {
    @Test public void exactWireBytesAcrossLengthBoundaries() throws Exception {
        int[] sizes = {0, 1, 125, 126, 127, 16380, 16381, 65535, 65536, 4 * 1024 * 1024};
        for (int size : sizes) {
            byte[] payload = payload(size);
            byte[] original = payload.clone();
            RecordingOutput output = new RecordingOutput();
            new BrowserWebSocketFrameWriter(output).writeFrame(2, payload);
            check(Arrays.equals(expectedFrame(2, payload), output.bytes.toByteArray()), "wire bytes for " + size);
            check(Arrays.equals(original, payload), "payload must not be mutated for " + size);
            check(output.singleWrites == 0, "no single-byte writes for " + size);
            check(output.flushEnds.equals(Arrays.asList(output.bytes.size())), "flush exactly once after entire frame");
        }
    }

    @Test public void headerAndFirstPayloadShareOneBoundedBulkWrite() throws Exception {
        for (int size : new int[] {0, 1, 125, 126, 16380, 16381, 65535, 65536}) {
            RecordingOutput output = new RecordingOutput();
            byte[] payload = payload(size);
            new BrowserWebSocketFrameWriter(output).writeFrame(2, payload);
            int headerLength = size < 126 ? 2 : size <= 65535 ? 4 : 10;
            Write first = output.writes.get(0);
            check(first.array.length == 16 * 1024, "prefix allocation is fixed at 16 KiB");
            check(first.offset == 0, "prefix starts at zero");
            check(first.length == Math.min(16 * 1024, headerLength + size), "header and first payload are coalesced");
            check(output.writes.size() == (size + headerLength <= 16 * 1024 ? 1 : 2), "at most two bulk writes");
        }
    }

    @Test public void largeTailUsesOriginalPayloadAndPrefixIsReused() throws Exception {
        RecordingOutput output = new RecordingOutput();
        BrowserWebSocketFrameWriter writer = new BrowserWebSocketFrameWriter(output);
        byte[] payload = payload(4 * 1024 * 1024);
        writer.writeFrame(2, payload);
        Write prefix = output.writes.get(0);
        Write tail = output.writes.get(1);
        check(tail.array == payload, "large tail must use original array, not a whole-frame copy");
        check(tail.offset == 16 * 1024 - 10, "64-bit header leaves expected prefix payload space");
        check(tail.length == payload.length - tail.offset, "entire remaining payload is written");
        writer.writeFrame(9, new byte[0]);
        check(output.writes.get(2).array == prefix.array, "scratch is reused across video and control frames");

        RecordingOutput other = new RecordingOutput();
        new BrowserWebSocketFrameWriter(other).writeFrame(10, new byte[0]);
        check(other.writes.get(0).array != prefix.array, "different connections never share mutable scratch");
    }

    @Test public void everyVideoTextAndControlFrameFlushesIndependently() throws Exception {
        RecordingOutput output = new RecordingOutput();
        BrowserWebSocketFrameWriter writer = new BrowserWebSocketFrameWriter(output);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        int[] opcodes = {2, 1, 9, 10, 8};
        byte[][] payloads = {payload(65536), "status".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            new byte[0], payload(125), new byte[] {3, (byte) 0xe8}};
        for (int index = 0; index < opcodes.length; ++index) {
            writer.writeFrame(opcodes[index], payloads[index]);
            expected.write(expectedFrame(opcodes[index], payloads[index]));
            check(output.flushEnds.size() == index + 1, "each frame flushes before the next is submitted");
            check(output.flushEnds.get(index) == expected.size(), "flush includes exactly this complete frame");
            check(Arrays.equals(expected.toByteArray(), output.bytes.toByteArray()), "no delayed or extra frame bytes");
        }
        check(output.singleWrites == 0, "controls also use bulk writes");
    }

    @Test public void writeFailurePropagatesWithoutFlushingPartialFrame() throws Exception {
        for (int failAt : new int[] {1, 2}) {
            final int failureCall = failAt;
            IOException failure = new IOException("synthetic write failure");
            int[] writes = {0}, flushes = {0};
            OutputStream output = new OutputStream() {
                @Override public void write(int value) { throw new AssertionError("single-byte write"); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (++writes[0] == failureCall) throw failure;
                }
                @Override public void flush() { ++flushes[0]; }
            };
            try {
                new BrowserWebSocketFrameWriter(output).writeFrame(2, payload(65536));
                throw new AssertionError("write failure must reach connection cleanup");
            } catch (IOException caught) {
                check(caught == failure, "preserve exact write failure");
            }
            check(writes[0] == failAt, "no write after failure");
            check(flushes[0] == 0, "never mark partial frame flushed");
        }
    }

    @Test public void flushFailurePropagatesToConnectionCleanup() throws Exception {
        IOException failure = new IOException("synthetic flush failure");
        OutputStream output = new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("single-byte write"); }
            @Override public void write(byte[] bytes, int offset, int length) { }
            @Override public void flush() throws IOException { throw failure; }
        };
        try {
            new BrowserWebSocketFrameWriter(output).writeFrame(9, new byte[0]);
            throw new AssertionError("flush failure must reach connection cleanup");
        } catch (IOException caught) {
            check(caught == failure, "preserve exact flush failure");
        }
    }

    private static byte[] payload(int size) {
        byte[] payload = new byte[size];
        for (int index = 0; index < size; ++index) payload[index] = (byte) (index * 37 + 11);
        return payload;
    }

    private static byte[] expectedFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(0x80 | opcode);
        if (payload.length < 126) {
            bytes.write(payload.length);
        } else if (payload.length <= 65535) {
            bytes.write(126);
            bytes.write(ByteBuffer.allocate(2).putShort((short) payload.length).array());
        } else {
            bytes.write(127);
            bytes.write(ByteBuffer.allocate(8).putLong(payload.length).array());
        }
        bytes.write(payload);
        return bytes.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Write {
        final byte[] array;
        final int offset;
        final int length;
        Write(byte[] array, int offset, int length) {
            this.array = array; this.offset = offset; this.length = length;
        }
    }

    private static final class RecordingOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Write> writes = new ArrayList<>();
        final List<Integer> flushEnds = new ArrayList<>();
        int singleWrites;
        @Override public void write(int value) { ++singleWrites; bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) {
            writes.add(new Write(value, offset, length));
            bytes.write(value, offset, length);
        }
        @Override public void flush() { flushEnds.add(bytes.size()); }
    }
}
