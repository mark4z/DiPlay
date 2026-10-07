package com.shilapi.xcertplay.mfi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/** Local picker input only. Runtime authentication and bundled assets remain raw-byte formats. */
final class LocalMfiImportEncoding {
    static final int MAX_FILE_BYTES = 16 * 1024;
    static final int MAX_TEXT_BYTES = 32 * 1024;

    private LocalMfiImportEncoding() {}

    /** Does not close the caller's stream. Reads at most the limit plus one overflow byte. */
    static byte[] read(InputStream input, boolean privateKey, boolean allowBase64,
            Runnable checkOpen) throws IOException {
        int limit = allowBase64 ? MAX_TEXT_BYTES : MAX_FILE_BYTES;
        byte[] buffer = new byte[limit + 1];
        byte[] selected = null;
        byte[] result = null;
        boolean returned = false;
        try {
            int size = 0;
            while (true) {
                checkOpen.run();
                int count = input.read(buffer, size, Math.min(1024, buffer.length - size));
                if (count < 0) break;
                if (count == 0) {
                    int next = input.read();
                    if (next < 0) break;
                    buffer[size] = (byte) next;
                    count = 1;
                }
                checkOpen.run();
                size += count;
                require(size <= limit, "Local identity input is too large");
            }
            checkOpen.run();
            require(size > 0, "Local identity file is empty");
            selected = Arrays.copyOf(buffer, size);
            result = allowBase64 ? normalize(selected, privateKey) : selected;
            checkOpen.run();
            returned = true;
            return result;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            if (selected != null && (selected != result || !returned)) Arrays.fill(selected, (byte) 0);
            if (result != null && !returned) Arrays.fill(result, (byte) 0);
        }
    }

    private static byte[] normalize(byte[] input, boolean privateKey) {
        // Parse the original first, so PEM certificates and all accepted binary certificate
        // containers retain their exact wire bytes. Never decode the body of a PEM certificate.
        if (input.length <= MAX_FILE_BYTES && isRaw(input, privateKey)) return input;
        byte[] decoded = decodeBase64(input);
        boolean accepted = false;
        try {
            // One decoding pass only. Curve, certificate count and pair matching are still
            // checked by the unchanged LocalMfiAuthenticationClient before atomic commit.
            require(isRaw(decoded, privateKey), "Invalid local identity file format");
            accepted = true;
            return decoded;
        } finally {
            if (!accepted) Arrays.fill(decoded, (byte) 0);
        }
    }

    private static boolean isRaw(byte[] input, boolean privateKey) {
        try {
            if (privateKey) {
                KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(input));
                return true;
            }
            return !CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(input)).isEmpty();
        } catch (Exception ignored) {
            // No key contents or provider errors are exposed to logs or UI.
            return false;
        }
    }

    /** Strict standard Base64 with ASCII whitespace, canonical padding and a decoded limit. */
    static byte[] decodeBase64(byte[] input) {
        require(input.length <= MAX_TEXT_BYTES, "Local identity input is too large");
        byte[] compact = new byte[input.length];
        byte[] encoded = null;
        try {
            int size = 0;
            for (byte value : input) {
                int ch = value & 0xff;
                if (ch == ' ' || (ch >= '\t' && ch <= '\r')) continue;
                require(sextet(ch) >= 0 || ch == '=', "Invalid standard Base64 identity file");
                compact[size++] = value;
            }
            require(size > 0 && size % 4 == 0, "Invalid Base64 identity padding");
            int padding = compact[size - 1] == '=' ? 1 : 0;
            if (compact[size - 2] == '=') padding++;
            for (int i = 0; i < size - padding; i++) {
                require(compact[i] != '=', "Invalid Base64 identity padding");
            }
            int decodedSize = size / 4 * 3 - padding;
            require(decodedSize > 0 && decodedSize <= MAX_FILE_BYTES, "Local identity file is too large");
            // JDK/Android decoders accept some non-canonical final sextets; reject those here.
            if (padding == 2) require((sextet(compact[size - 3]) & 15) == 0, "Invalid Base64 identity padding");
            if (padding == 1) require((sextet(compact[size - 2]) & 3) == 0, "Invalid Base64 identity padding");
            encoded = Arrays.copyOf(compact, size);
            return Base64.getDecoder().decode(encoded);
        } finally {
            Arrays.fill(compact, (byte) 0);
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    private static int sextet(int ch) {
        if (ch >= 'A' && ch <= 'Z') return ch - 'A';
        if (ch >= 'a' && ch <= 'z') return ch - 'a' + 26;
        if (ch >= '0' && ch <= '9') return ch - '0' + 52;
        if (ch == '+') return 62;
        if (ch == '/') return 63;
        return -1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
