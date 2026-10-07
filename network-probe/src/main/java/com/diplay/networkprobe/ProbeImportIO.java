package com.diplay.networkprobe;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Bounded in-memory reads. The caller registers provider streams before reading them. */
final class ProbeImportIO {
    private ProbeImportIO() {}
    static byte[] read(InputStream input, int limit, ProbeSession task) throws IOException {
        if (input == null) throw new IOException("IMPORT_READ_FAILED");
        if (!task.own(input)) throw new IOException("IMPORT_CANCELLED");
        byte[] buffer = new byte[limit + 1];
        try {
            int length = 0;
            while (length < buffer.length) {
                if (task.isCancelled()) throw new IOException("IMPORT_CANCELLED");
                int count = input.read(buffer, length, buffer.length - length);
                if (count < 0) return Arrays.copyOf(buffer, length);
                if (count == 0) continue;
                length += count;
            }
            throw new IOException("IMPORT_FILE_TOO_LARGE");
        } finally {
            Arrays.fill(buffer, (byte) 0);
            task.closeOwned(input);
        }
    }
}
