package com.shilapi.xcertplay.browser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.X509TrustManager;

/**
 * Bounded phone-local ZIP import. Nothing is extracted, persisted, logged or fetched. Entry names
 * never select credentials: exactly one certificate PEM and one key PEM are recognized by content.
 * The caller owns and must wipe the compressed input. Temporary plaintext arrays owned here are
 * wiped on every exit; Java's ZIP and crypto providers may retain uncontrollable internal copies.
 */
public final class BrowserTlsBundle {
    public static final int MAX_ARCHIVE_BYTES = 512 * 1024;
    public static final int MAX_ENTRIES = 16;
    public static final int MAX_TOTAL_BYTES = 128 * 1024;
    public static final int MAX_ENTRY_BYTES = 64 * 1024;
    private static final long LOCAL = 0x04034b50L, CENTRAL = 0x02014b50L;
    private static final long END = 0x06054b50L, DESCRIPTOR = 0x08074b50L;

    /** Fixed codes only. No archive name, provider exception, PEM or key bytes are exposed. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        private Failure(String code) { super(code); this.code = code; }
    }

    private BrowserTlsBundle() { }

    public static BrowserTlsIdentity read(byte[] zip, String hostname) throws Failure {
        return readInternal(zip, hostname, null, false);
    }

    /** Test seam only. Production callers must always use read and normal platform trust. */
    static BrowserTlsIdentity readWithTrust(byte[] zip, String hostname, X509TrustManager trust)
            throws Failure {
        return readInternal(zip, hostname, trust, true);
    }

    private static BrowserTlsIdentity readInternal(byte[] zip, String hostname,
            X509TrustManager trust, boolean testing) throws Failure {
        if (zip == null || zip.length == 0) throw fail("ZIP_EMPTY");
        if (zip.length > MAX_ARCHIVE_BYTES) throw fail("ZIP_TOO_LARGE");
        byte[] scratch = new byte[MAX_ENTRY_BYTES];
        byte[] chain = null, key = null;
        try {
            Record[] records = checkStructure(zip);
            int total = 0, count = 0;
            // Closing this in-memory stream before identity validation also closes its inflater.
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    if (count >= MAX_ENTRIES || count >= records.length) throw fail("ZIP_ENTRY_COUNT");
                    Record expected = records[count++];
                    checkName(entry.getName());
                    int length = 0;
                    BrowserTlsIdentity.PemKind kind = BrowserTlsIdentity.PemKind.UNKNOWN;
                    while (true) {
                        // A one-byte overrun probe prevents silent truncation at the exact limit.
                        if (length == scratch.length) {
                            if (input.read() != -1) throw fail("ZIP_ENTRY_TOO_LARGE");
                            break;
                        }
                        int read = input.read(scratch, length, Math.min(4096, scratch.length - length));
                        if (read == -1) break;
                        if (read == 0) throw fail("ZIP_UNREADABLE");
                        length += read;
                        total += read;
                        if (total > MAX_TOTAL_BYTES) throw fail("ZIP_TOTAL_TOO_LARGE");
                        kind = BrowserTlsIdentity.classifyPem(scratch);
                        if (kind == BrowserTlsIdentity.PemKind.PRIVATE_KEY
                                && length > BrowserTlsIdentity.MAX_KEY_BYTES) throw fail("KEY_TOO_LARGE");
                    }
                    // ZipInputStream verifies each entry's local/data-descriptor CRC while reading.
                    // Also bind what it read to the independently checked central directory.
                    input.closeEntry();
                    if (length != expected.size || entry.getCrc() != expected.crc
                            || entry.getCompressedSize() != expected.compressedSize
                            || entry.getMethod() != expected.method) throw fail("ZIP_MALFORMED");
                    if (entry.isDirectory()) {
                        if (length != 0) throw fail("ZIP_DIRECTORY_NOT_EMPTY");
                    } else if (length == 0) {
                        throw fail("ZIP_ENTRY_EMPTY");
                    } else if (kind == BrowserTlsIdentity.PemKind.CERTIFICATE) {
                        if (chain != null) throw fail("ZIP_MULTIPLE_CHAINS");
                        chain = Arrays.copyOf(scratch, length);
                    } else if (kind == BrowserTlsIdentity.PemKind.PRIVATE_KEY) {
                        if (key != null) throw fail("ZIP_MULTIPLE_KEYS");
                        key = Arrays.copyOf(scratch, length);
                    } else {
                        throw fail("ZIP_ENTRY_UNSUPPORTED");
                    }
                    Arrays.fill(scratch, (byte) 0);
                }
                if (count != records.length) throw fail("ZIP_MALFORMED");
            }
            if (chain == null) throw fail("ZIP_CHAIN_MISSING");
            if (key == null) throw fail("ZIP_KEY_MISSING");
            // Every entry and CRC has been consumed before expensive identity checks start.
            return testing ? BrowserTlsIdentity.readWithTrust(chain, key, hostname, trust)
                    : BrowserTlsIdentity.read(chain, key, hostname);
        } catch (Failure failure) {
            throw fail(failure.code);
        } catch (BrowserTlsIdentity.Failure failure) {
            throw fail(failure.code);
        } catch (IOException | IllegalArgumentException failure) {
            throw fail("ZIP_UNREADABLE");
        } finally {
            Arrays.fill(scratch, (byte) 0);
            if (chain != null) Arrays.fill(chain, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    /** ZIP32 only: reject encryption, split archives, hidden entries, truncation and ZIP64. */
    private static Record[] checkStructure(byte[] zip) throws Failure {
        int end = -1;
        for (int i = zip.length - 22; i >= Math.max(0, zip.length - 65557); i--) {
            if (u32(zip, i) == END && i + 22L + u16(zip, i + 20) == zip.length) {
                end = i;
                break;
            }
        }
        if (end < 0) throw fail("ZIP_MALFORMED");
        if (u16(zip, end + 4) != 0 || u16(zip, end + 6) != 0) throw fail("ZIP_UNSUPPORTED");
        int count = u16(zip, end + 10);
        if (count != u16(zip, end + 8)) throw fail("ZIP_MALFORMED");
        if (count > MAX_ENTRIES) throw fail("ZIP_ENTRY_COUNT");
        long centralOffset = u32(zip, end + 16), centralSize = u32(zip, end + 12);
        if (centralOffset + centralSize != end) throw fail("ZIP_MALFORMED");
        if (centralOffset > end) throw fail("ZIP_MALFORMED");
        Record[] records = new Record[count];
        int at = (int) centralOffset;
        long total = 0;
        for (int i = 0; i < count; i++) {
            requireRange(at, 46, end);
            if (u32(zip, at) != CENTRAL) throw fail("ZIP_MALFORMED");
            int flags = u16(zip, at + 8), method = u16(zip, at + 10);
            checkFormat(u16(zip, at + 6), flags, method);
            int nameLength = u16(zip, at + 28), extraLength = u16(zip, at + 30);
            int commentLength = u16(zip, at + 32);
            requireRange(at + 46, nameLength + extraLength + commentLength, end);
            if (u16(zip, at + 34) != 0) throw fail("ZIP_UNSUPPORTED");
            checkRawName(zip, at + 46, nameLength);
            checkExtra(zip, at + 46 + nameLength, extraLength);
            long size = u32(zip, at + 24), compressedSize = u32(zip, at + 20);
            long localOffset = u32(zip, at + 42);
            if (size > MAX_ENTRY_BYTES) throw fail("ZIP_ENTRY_TOO_LARGE");
            if (compressedSize > zip.length || localOffset > centralOffset) throw fail("ZIP_MALFORMED");
            total += size;
            if (total > MAX_TOTAL_BYTES) throw fail("ZIP_TOTAL_TOO_LARGE");
            records[i] = new Record((int) localOffset, at + 46, nameLength, flags, method,
                    (int) size, compressedSize, u32(zip, at + 16));
            at += 46 + nameLength + extraLength + commentLength;
        }
        if (at != end) throw fail("ZIP_MALFORMED");
        Arrays.sort(records, Comparator.comparingInt(record -> record.localOffset));
        if (count == 0 ? centralOffset != 0 : records[0].localOffset != 0) throw fail("ZIP_MALFORMED");
        for (int i = 0; i < count; i++) {
            Record record = records[i];
            int next = i + 1 < count ? records[i + 1].localOffset : (int) centralOffset;
            checkLocal(zip, record, next);
        }
        return records;
    }

    private static void checkLocal(byte[] zip, Record record, int next) throws Failure {
        int at = record.localOffset;
        requireRange(at, 30, next);
        if (u32(zip, at) != LOCAL) throw fail("ZIP_MALFORMED");
        int flags = u16(zip, at + 6), method = u16(zip, at + 8);
        checkFormat(u16(zip, at + 4), flags, method);
        if (flags != record.flags || method != record.method) throw fail("ZIP_MALFORMED");
        int nameLength = u16(zip, at + 26), extraLength = u16(zip, at + 28);
        requireRange(at + 30, nameLength + extraLength, next);
        if (nameLength != record.nameLength) throw fail("ZIP_MALFORMED");
        for (int i = 0; i < nameLength; i++) {
            if (zip[at + 30 + i] != zip[record.nameOffset + i]) throw fail("ZIP_MALFORMED");
        }
        checkExtra(zip, at + 30 + nameLength, extraLength);
        long dataEnd = at + 30L + nameLength + extraLength + record.compressedSize;
        if (dataEnd > next) throw fail("ZIP_MALFORMED");
        long crc = u32(zip, at + 14), compressed = u32(zip, at + 18), size = u32(zip, at + 22);
        if ((flags & 8) == 0) {
            if (dataEnd != next || crc != record.crc || compressed != record.compressedSize
                    || size != record.size) throw fail("ZIP_MALFORMED");
        } else {
            if ((crc != 0 && crc != record.crc) || (compressed != 0 && compressed != record.compressedSize)
                    || (size != 0 && size != record.size)) throw fail("ZIP_MALFORMED");
            int descriptor = (int) dataEnd;
            if (next - descriptor == 16 && u32(zip, descriptor) == DESCRIPTOR) descriptor += 4;
            if (next - descriptor != 12 || u32(zip, descriptor) != record.crc
                    || u32(zip, descriptor + 4) != record.compressedSize
                    || u32(zip, descriptor + 8) != record.size) throw fail("ZIP_MALFORMED");
        }
    }

    private static void checkFormat(int version, int flags, int method) throws Failure {
        if ((flags & (1 | 64 | 8192)) != 0) throw fail("ZIP_ENCRYPTED");
        if (version > 20 || (flags & ~(2048 | 8 | 6)) != 0
                || (method != ZipEntry.STORED && method != ZipEntry.DEFLATED)
                || (method == ZipEntry.STORED && (flags & (8 | 6)) != 0)) throw fail("ZIP_UNSUPPORTED");
    }

    private static void checkExtra(byte[] zip, int at, int length) throws Failure {
        int end = at + length;
        while (at < end) {
            requireRange(at, 4, end);
            int tag = u16(zip, at), size = u16(zip, at + 2);
            requireRange(at + 4, size, end);
            if (tag == 1) throw fail("ZIP_UNSUPPORTED");
            if (tag == 0x9901) throw fail("ZIP_ENCRYPTED");
            at += 4 + size;
        }
    }

    private static void checkRawName(byte[] zip, int at, int length) throws Failure {
        if (length == 0 || zip[at] == '/') throw fail("ZIP_PATH_UNSAFE");
        int segment = at;
        for (int i = at; i <= at + length; i++) {
            if (i < at + length && (zip[i] == 0 || zip[i] == '\\' || zip[i] == ':')) throw fail("ZIP_PATH_UNSAFE");
            if (i == at + length || zip[i] == '/') {
                if ((i - segment == 1 && zip[segment] == '.')
                        || (i - segment == 2 && zip[segment] == '.' && zip[segment + 1] == '.')) throw fail("ZIP_PATH_UNSAFE");
                segment = i + 1;
            }
        }
    }

    private static void checkName(String name) throws Failure {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0) throw fail("ZIP_PATH_UNSAFE");
        for (String part : name.split("/")) {
            if (part.equals(".") || part.equals("..")) throw fail("ZIP_PATH_UNSAFE");
        }
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8);
    }
    private static long u32(byte[] bytes, int at) {
        return (long) u16(bytes, at) | ((long) u16(bytes, at + 2) << 16);
    }
    private static void requireRange(int at, int size, int end) throws Failure {
        if (at < 0 || size < 0 || at > end || size > end - at) throw fail("ZIP_MALFORMED");
    }
    private static Failure fail(String code) { return new Failure(code); }

    /** Only archive metadata, never plaintext entry content. */
    private static final class Record {
        final int localOffset, nameOffset, nameLength, flags, method, size;
        final long compressedSize, crc;
        Record(int localOffset, int nameOffset, int nameLength, int flags, int method,
                int size, long compressedSize, long crc) {
            this.localOffset = localOffset;
            this.nameOffset = nameOffset;
            this.nameLength = nameLength;
            this.flags = flags;
            this.method = method;
            this.size = size;
            this.compressedSize = compressedSize;
            this.crc = crc;
        }
    }
}
