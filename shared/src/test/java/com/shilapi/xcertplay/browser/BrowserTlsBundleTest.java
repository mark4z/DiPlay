package com.shilapi.xcertplay.browser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Every ZIP and identity is generated in memory at runtime; no credential/archive files exist. */
public class BrowserTlsBundleTest {
    @BeforeClass public static void syntheticFixtures() throws Exception {
        BrowserTlsIdentityTest.syntheticFixtures();
    }

    @Test public void importsGeneratedPkcs8AndPkcs1ByContentWithMisleadingNames() throws Exception {
        for (byte[] key : new byte[][] { key(), BrowserTlsIdentityTest.bundlePkcs1() }) {
            byte[] zip = zip(entry("key.crt", chain()), entry("certificate.pem", key));
            try {
                BrowserTlsIdentity identity = read(zip);
                assertTrue(identity.description().startsWith("RSA 2048 bits; expires "));
                assertTrue(identity.supportsHostname(BrowserHttpsPolicy.HOSTNAME));
            } finally { wipe(zip, key); }
        }
    }

    @Test public void acceptsNestedBenignDirectoryAndReversedOrder() throws Exception {
        byte[] zip = zip(entry("nginx/", new byte[0]), entry("nginx/deploy/", new byte[0]),
                entry("nginx/deploy/first.data", key()), entry("nginx/deploy/second.data", chain()));
        try { assertNotNull(read(zip)); } finally { wipe(zip); }
    }

    @Test public void storedArchivesAreAlsoSupported() throws Exception {
        byte[] zip = zipStored(entry("fullchain", chain()), entry("private", key()));
        try { assertNotNull(read(zip)); } finally { wipe(zip); }
    }

    @Test public void acceptsArchiveCommentAndCentralDirectoryReordering() throws Exception {
        byte[] original = zip(entry("chain", chain()), entry("key", key()));
        byte[] commented = concat(original, ascii("generated archive comment"));
        put16(commented, original.length - 2, commented.length - original.length);
        try { assertNotNull(read(commented)); } finally { wipe(commented); }
        int central = central(original);
        int firstLength = 46 + get16(original, central + 28) + get16(original, central + 30)
                + get16(original, central + 32);
        int second = central + firstLength;
        int secondLength = original.length - 22 - second;
        byte[] reordered = original.clone();
        System.arraycopy(original, second, reordered, central, secondLength);
        System.arraycopy(original, central, reordered, central + secondLength, firstLength);
        try { assertNotNull(read(reordered)); } finally { wipe(original, reordered); }
    }

    @Test public void acceptsBomAndWhitespaceWithoutChangingIdentityValidation() throws Exception {
        byte[] prefix = { (byte) 0xef, (byte) 0xbb, (byte) 0xbf, ' ', '\t', '\r', '\n' };
        byte[] zip = zip(entry("certificate", concat(prefix, chain())), entry("key", concat(prefix, key())));
        try { assertNotNull(read(zip)); } finally { wipe(zip); }
    }

    @Test public void acceptsExactEntryAndKeyLimits() throws Exception {
        byte[] zip = zip(entry("fullchain", pad(chain(), BrowserTlsIdentity.MAX_CHAIN_BYTES)),
                entry("key", pad(key(), BrowserTlsIdentity.MAX_KEY_BYTES)));
        try { assertNotNull(read(zip)); } finally { wipe(zip); }
    }

    @Test public void acceptsExactlySixteenEntriesIncludingDirectories() throws Exception {
        Entry[] entries = new Entry[BrowserTlsBundle.MAX_ENTRIES];
        for (int i = 0; i < entries.length - 2; i++) entries[i] = entry("directory" + i + "/", new byte[0]);
        entries[entries.length - 2] = entry("chain", chain());
        entries[entries.length - 1] = entry("key", key());
        byte[] zip = zip(entries);
        try { assertNotNull(read(zip)); } finally { wipe(zip); }
    }

    @Test public void preservesProductionTrustAndHostnameChecks() throws Exception {
        byte[] zip = zip(entry("fullchain", chain()), entry("private", key()));
        try {
            assertEquals("CHAIN_UNTRUSTED", assertThrows(BrowserTlsBundle.Failure.class,
                    () -> BrowserTlsBundle.read(zip, BrowserHttpsPolicy.HOSTNAME)).code);
            assertEquals("IDENTITY_HOST_UNSUPPORTED", assertThrows(BrowserTlsBundle.Failure.class,
                    () -> BrowserTlsBundle.readWithTrust(zip, "test.mark4z.asia",
                            BrowserTlsIdentityTest.bundleTrust())).code);
            assertEquals("IDENTITY_HOST_UNSUPPORTED", assertThrows(BrowserTlsBundle.Failure.class,
                    () -> BrowserTlsBundle.readWithTrust(zip, "other.invalid",
                            BrowserTlsIdentityTest.bundleTrust())).code);
        } finally { wipe(zip); }
    }

    @Test public void preservesKeyMismatchAndStageSpecificParserCodes() throws Exception {
        failure("KEY_MISMATCH", zip(entry("chain", chain()),
                entry("key", BrowserTlsIdentityTest.bundleMismatchedPkcs8())));
        failure("KEY_PEM_MALFORMED", zip(entry("chain", chain()),
                entry("key", ascii("-----BEGIN PRIVATE KEY-----\n***\n-----END PRIVATE KEY-----\n"))));
        failure("CERT_PEM_MALFORMED", zip(entry("chain", ascii("-----BEGIN CERTIFICATE-----\n***\n-----END CERTIFICATE-----\n")),
                entry("key", key())));
    }

    @Test public void preservesSpecificUnsupportedKeyContainerCodes() throws Exception {
        failure("KEY_ENCRYPTED", zip(entry("chain", chain()),
                entry("key", ascii("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAA==\n-----END ENCRYPTED PRIVATE KEY-----\n"))));
        failure("KEY_SEC1_UNSUPPORTED", zip(entry("chain", chain()),
                entry("key", ascii("-----BEGIN EC PRIVATE KEY-----\nAA==\n-----END EC PRIVATE KEY-----\n"))));
    }

    @Test public void rejectsMissingChainOrKey() throws Exception {
        failure("ZIP_CHAIN_MISSING", zip());
        failure("ZIP_CHAIN_MISSING", zip(entry("directory/", new byte[0])));
        failure("ZIP_CHAIN_MISSING", zip(entry("key", key())));
        failure("ZIP_KEY_MISSING", zip(entry("chain", chain())));
    }

    @Test public void rejectsExtraUnknownAndEmptyFiles() throws Exception {
        failure("ZIP_ENTRY_UNSUPPORTED", zip(entry("chain", chain()), entry("key", key()),
                entry("README.txt", ascii("unexpected extra file"))));
        failure("ZIP_ENTRY_EMPTY", zip(entry("chain", chain()), entry("key", key()),
                entry("empty.txt", new byte[0])));
        failure("ZIP_DIRECTORY_NOT_EMPTY", zip(entry("not-a-directory/", chain())));
    }

    @Test public void rejectsNestedArchivesAndLabelsHiddenBehindPreamble() throws Exception {
        byte[] nested = zip(entry("chain", chain()), entry("key", key()));
        failure("ZIP_ENTRY_UNSUPPORTED", zip(entry("nginx.zip", nested)));
        failure("ZIP_ENTRY_UNSUPPORTED", zip(entry("chain.pem", concat(ascii("preamble\n"), chain())),
                entry("key", key())));
    }

    @Test public void rejectsMultipleChainFilesAndMultipleKeyFiles() throws Exception {
        failure("ZIP_MULTIPLE_CHAINS", zip(entry("cert.pem", chain()), entry("fullchain.pem", chain()), entry("key", key())));
        failure("ZIP_MULTIPLE_KEYS", zip(entry("chain", chain()), entry("key1", key()), entry("key2", key())));
    }

    @Test public void rejectsDuplicateIdentitiesEvenWithSameEntryNames() throws Exception {
        byte[] zip = zipStored(entry("chain", chain()), entry("keyA", key()), entry("keyB", key()));
        // ZipOutputStream disallows duplicate names, so change only generated archive metadata.
        replaceName(zip, ascii("keyB"), ascii("keyA"));
        failure("ZIP_MULTIPLE_KEYS", zip);
    }

    @Test public void rejectsTraversalAbsoluteBackslashNulAndDrivePaths() throws Exception {
        for (String path : new String[] { "../chain", "nginx/../chain", "/chain", "C:/chain",
                "nginx\\chain", "bad\0chain", "./chain", "nginx/./chain", "../" }) {
            failure("ZIP_PATH_UNSAFE", zip(entry(path, chain()), entry("key", key())));
        }
    }

    @Test public void rejectsCompressedInputAndEntryCountLimits() throws Exception {
        failure("ZIP_EMPTY", null);
        failure("ZIP_EMPTY", new byte[0]);
        failure("ZIP_TOO_LARGE", new byte[BrowserTlsBundle.MAX_ARCHIVE_BYTES + 1]);
        Entry[] entries = new Entry[BrowserTlsBundle.MAX_ENTRIES + 1];
        for (int i = 0; i < entries.length; i++) entries[i] = entry("directory" + i + "/", new byte[0]);
        failure("ZIP_ENTRY_COUNT", zip(entries));
    }

    @Test public void rejectsEntryKeyAndAggregateUncompressedLimits() throws Exception {
        failure("ZIP_ENTRY_TOO_LARGE", zip(entry("chain", pad(chain(), BrowserTlsBundle.MAX_ENTRY_BYTES + 1)), entry("key", key())));
        failure("KEY_TOO_LARGE", zip(entry("chain", chain()), entry("key", pad(key(), BrowserTlsIdentity.MAX_KEY_BYTES + 1))));
        failure("ZIP_TOTAL_TOO_LARGE", zip(entry("one", pad(chain(), 45000)),
                entry("two", pad(chain(), 45000)), entry("three", pad(chain(), 45000))));
    }

    @Test public void boundsInflationEvenWhenUncompressedMetadataLies() throws Exception {
        byte[] huge = new byte[1024 * 1024];
        Arrays.fill(huge, (byte) ' ');
        byte[] zip = zip(entry("huge", huge));
        int central = central(zip);
        int compressed = get32(zip, central + 20);
        int data = 30 + get16(zip, 26) + get16(zip, 28);
        put32(zip, central + 24, 1);
        // Generated deflated entries have a signed 16-byte data descriptor.
        put32(zip, data + compressed + 12, 1);
        failure("ZIP_ENTRY_TOO_LARGE", zip);
    }

    @Test public void rejectsEncryptedFlagsAndUnsupportedCompression() throws Exception {
        for (int encryptedFlag : new int[] { 1, 64, 8192 }) {
            byte[] zip = zip(entry("chain", chain()), entry("key", key()));
            put16(zip, 6, get16(zip, 6) | encryptedFlag);
            put16(zip, central(zip) + 8, get16(zip, central(zip) + 8) | encryptedFlag);
            failure("ZIP_ENCRYPTED", zip);
        }
        byte[] unsupported = zip(entry("chain", chain()), entry("key", key()));
        put16(unsupported, 8, 99);
        put16(unsupported, central(unsupported) + 10, 99);
        failure("ZIP_UNSUPPORTED", unsupported);
    }

    @Test public void rejectsCorruptCrcBeforeTryingTheIdentity() throws Exception {
        byte[] zip = zipStored(entry("chain", chain()), entry("key", key()));
        int data = 30 + get16(zip, 26) + get16(zip, 28);
        zip[data + 50] ^= 1;
        failure("ZIP_UNREADABLE", zip);
    }

    @Test public void validatesLaterEntriesAndCrcBeforeIdentityParsing() throws Exception {
        byte[] zip = zipStored(entry("chain", ascii("-----BEGIN CERTIFICATE-----\n***\n-----END CERTIFICATE-----\n")),
                entry("key", key()));
        int second = central(zip) + 46 + get16(zip, central(zip) + 28);
        int local = get32(zip, second + 42);
        int data = local + 30 + get16(zip, local + 26) + get16(zip, local + 28);
        zip[data + 50] ^= 1;
        failure("ZIP_UNREADABLE", zip);
        failure("ZIP_ENTRY_UNSUPPORTED", zip(entry("chain", ascii("-----BEGIN CERTIFICATE-----\n***\n-----END CERTIFICATE-----\n")),
                entry("key", key()), entry("README", ascii("unexpected"))));
    }

    @Test public void rejectsTruncatedArchiveCentralMetadataMismatchAndTrailingJunk() throws Exception {
        byte[] valid = zip(entry("chain", chain()), entry("key", key()));
        try {
            failure("ZIP_MALFORMED", Arrays.copyOf(valid, valid.length - 1));
            failure("ZIP_MALFORMED", Arrays.copyOf(valid, central(valid)));
            failure("ZIP_MALFORMED", concat(valid, new byte[] { 0 }));
            byte[] crc = valid.clone();
            crc[central(crc) + 16] ^= 1;
            failure("ZIP_MALFORMED", crc);
            byte[] name = valid.clone();
            name[central(name) + 46] ^= 1;
            failure("ZIP_MALFORMED", name);
        } finally { wipe(valid); }
    }

    @Test public void rejectsPrefixHiddenEntryAndSplitArchive() throws Exception {
        byte[] valid = zipStored(entry("chain", chain()), entry("key", key()));
        try {
            failure("ZIP_MALFORMED", concat(new byte[] { 0 }, valid));
            byte[] split = valid.clone();
            put16(split, split.length - 22 + 4, 1);
            failure("ZIP_UNSUPPORTED", split);
            byte[] hidden = valid.clone();
            int end = hidden.length - 22;
            put16(hidden, end + 8, 1);
            put16(hidden, end + 10, 1);
            failure("ZIP_MALFORMED", hidden);
        } finally { wipe(valid); }
    }

    @Test public void doesNotMutateCallerInputAndExposesNoProviderCauses() throws Exception {
        byte[] zip = zip(entry("chain", chain()), entry("key", key()));
        byte[] original = zip.clone();
        try {
            read(zip);
            assertArrayEquals(original, zip);
            BrowserTlsBundle.Failure failure = assertThrows(BrowserTlsBundle.Failure.class,
                    () -> BrowserTlsBundle.read(zip, BrowserHttpsPolicy.HOSTNAME));
            assertEquals(failure.code, failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertArrayEquals(original, zip);
        } finally { wipe(zip, original); }
    }

    @Test public void boundedMalformedArchiveCorpusOnlyProducesSafeCodes() throws Exception {
        Random random = new Random(20261007L);
        for (int i = 0; i < 128; i++) {
            byte[] bytes = new byte[1 + random.nextInt(2048)];
            random.nextBytes(bytes);
            try {
                BrowserTlsBundle.Failure result = assertThrows(BrowserTlsBundle.Failure.class, () -> read(bytes));
                assertTrue(result.code.matches("[A-Z_]+"));
                assertNull(result.getCause());
                assertEquals(0, result.getSuppressed().length);
            } finally { wipe(bytes); }
        }
    }

    @Test public void generatedArchiveMutationCorpusNeverLeaksUncheckedErrors() throws Exception {
        Random random = new Random(20261008L);
        byte[] original = zip(entry("chain", chain()), entry("key", key()));
        try {
            for (int i = 0; i < 256; i++) {
                byte[] mutated = original.clone();
                for (int j = 0; j <= i % 4; j++) mutated[random.nextInt(mutated.length)] ^= (byte) (1 + random.nextInt(255));
                try {
                    // Metadata such as timestamps/comments may change without invalidating a ZIP.
                    read(mutated);
                } catch (BrowserTlsBundle.Failure failure) {
                    assertTrue(failure.code.matches("[A-Z_]+"));
                    assertEquals(failure.code, failure.getMessage());
                    assertNull(failure.getCause());
                    assertEquals(0, failure.getSuppressed().length);
                } finally { wipe(mutated); }
            }
        } finally { wipe(original); }
    }

    private static BrowserTlsIdentity read(byte[] zip) throws Exception {
        return BrowserTlsBundle.readWithTrust(zip, BrowserHttpsPolicy.HOSTNAME, BrowserTlsIdentityTest.bundleTrust());
    }
    private static byte[] chain() throws Exception { return BrowserTlsIdentityTest.bundleChain(); }
    private static byte[] key() { return BrowserTlsIdentityTest.bundlePkcs8(); }
    private static void failure(String code, byte[] zip) throws Exception {
        try {
            BrowserTlsBundle.Failure result = assertThrows(BrowserTlsBundle.Failure.class, () -> read(zip));
            assertEquals(code, result.code);
            assertEquals(code, result.getMessage());
            assertNull(result.getCause());
            assertEquals(0, result.getSuppressed().length);
        } finally { wipe(zip); }
    }
    private static Entry entry(String name, byte[] bytes) { return new Entry(name, bytes); }
    private static byte[] zip(Entry... entries) throws Exception { return makeZip(false, entries); }
    private static byte[] zipStored(Entry... entries) throws Exception { return makeZip(true, entries); }
    private static byte[] makeZip(boolean stored, Entry... entries) throws Exception {
        WipingOutput output = new WipingOutput();
        try {
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (Entry entry : entries) {
                    ZipEntry target = new ZipEntry(entry.name);
                    if (stored) {
                        CRC32 crc = new CRC32();
                        crc.update(entry.bytes);
                        target.setMethod(ZipEntry.STORED);
                        target.setSize(entry.bytes.length);
                        target.setCompressedSize(entry.bytes.length);
                        target.setCrc(crc.getValue());
                    }
                    zip.putNextEntry(target);
                    zip.write(entry.bytes);
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } finally {
            output.wipe();
            for (Entry entry : entries) wipe(entry.bytes);
        }
    }
    private static byte[] pad(byte[] bytes, int size) {
        try {
            byte[] padded = Arrays.copyOf(bytes, size);
            Arrays.fill(padded, bytes.length, size, (byte) ' ');
            return padded;
        } finally { wipe(bytes); }
    }
    private static byte[] concat(byte[] first, byte[] second) {
        byte[] output = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, output, first.length, second.length);
        return output;
    }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static int get16(byte[] bytes, int at) { return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8); }
    private static int get32(byte[] bytes, int at) { return get16(bytes, at) | (get16(bytes, at + 2) << 16); }
    private static void put16(byte[] bytes, int at, int value) { bytes[at] = (byte) value; bytes[at + 1] = (byte) (value >>> 8); }
    private static void put32(byte[] bytes, int at, int value) { put16(bytes, at, value); put16(bytes, at + 2, value >>> 16); }
    private static int central(byte[] zip) { return get32(zip, zip.length - 22 + 16); }
    private static void replaceName(byte[] zip, byte[] from, byte[] to) {
        for (int i = 0; i <= zip.length - from.length; i++) {
            boolean match = true;
            for (int j = 0; j < from.length; j++) if (zip[i + j] != from[j]) match = false;
            if (match) System.arraycopy(to, 0, zip, i, to.length);
        }
    }
    private static void wipe(byte[]... values) { for (byte[] value : values) if (value != null) Arrays.fill(value, (byte) 0); }
    private static final class Entry {
        final String name;
        final byte[] bytes;
        Entry(String name, byte[] bytes) { this.name = name; this.bytes = bytes; }
    }
    private static final class WipingOutput extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
