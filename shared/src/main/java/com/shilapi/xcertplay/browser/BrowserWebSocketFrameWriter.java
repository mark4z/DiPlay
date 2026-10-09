package com.shilapi.xcertplay.browser;

import java.io.IOException;
import java.io.OutputStream;

/** Single-writer, unmasked WebSocket output; the outbound queue validates frame limits. */
final class BrowserWebSocketFrameWriter {
    // Reused per connection, not per frame. Coalesce the header with the first TLS-sized
    // payload block without allocating/copying an entire large video frame.
    private final byte[] prefix = new byte[16 * 1024];
    private final OutputStream output;

    BrowserWebSocketFrameWriter(OutputStream output) {
        this.output = output;
    }

    void writeFrame(int opcode, byte[] payload) throws IOException {
        prefix[0] = (byte) (0x80 | opcode);
        final int headerLength;
        if (payload.length < 126) {
            prefix[1] = (byte) payload.length;
            headerLength = 2;
        } else if (payload.length <= 65535) {
            prefix[1] = 126;
            prefix[2] = (byte) (payload.length >>> 8);
            prefix[3] = (byte) payload.length;
            headerLength = 4;
        } else {
            prefix[1] = 127;
            long length = payload.length;
            for (int index = 9; index >= 2; --index) {
                prefix[index] = (byte) length;
                length >>>= 8;
            }
            headerLength = 10;
        }
        int firstPayloadLength = Math.min(payload.length, prefix.length - headerLength);
        System.arraycopy(payload, 0, prefix, headerLength, firstPayloadLength);
        output.write(prefix, 0, headerLength + firstPayloadLength);
        if (firstPayloadLength < payload.length) {
            output.write(payload, firstPayloadLength, payload.length - firstPayloadLength);
        }
        // Never wait for another frame, including small controls or empty ping/pong.
        // Completion/queue credits remain after this flush in the connection writer.
        output.flush();
    }
}
