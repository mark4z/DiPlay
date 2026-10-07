package com.shilapi.xcertplay.mfi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.security.Signature;
import javax.security.auth.x500.X500Principal;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic bytes and fresh in-memory keys only; no production identity fixtures. */
public class LocalMfiImportEncodingTest {
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair().getPrivate().getEncoded();
    }
    private static byte[] read(byte[] bytes) throws Exception {
        return LocalMfiImportEncoding.read(new ByteArrayInputStream(bytes), true, true, () -> {});
    }

    @Test public void standardBase64VectorsDecodeExactly() {
        String[] encoded = {"Zg==", "Zm8=", "Zm9v", "Zm9vYg==", "Zm9vYmE=", "Zm9vYmFy", "+/8="};
        for (String value : encoded) {
            assertArrayEquals(Base64.getDecoder().decode(value), LocalMfiImportEncoding.decodeBase64(ascii(value)));
        }
    }

    @Test public void asciiWhitespaceIncludingCrLfAndSpacesIsAccepted() {
        assertArrayEquals(ascii("foobar"), LocalMfiImportEncoding.decodeBase64(ascii(" \tZ m\r\n9 v\fY\u000bm F y \r\n")));
        assertArrayEquals(ascii("f"), LocalMfiImportEncoding.decodeBase64(ascii("Z g =\r\n= ")));
    }

    @Test public void rejectsInvalidPaddingAndNonCanonicalPadBits() {
        for (String value : new String[] {"", " \r\n", "Z", "Zg", "Zg=", "Zg===", "Z=g=", "====", "A===",
                "Zg==AAAA", "AA=A", "Zh==", "Zm9=", "=AAA", "AA==AA=="}) {
            assertThrows(IllegalArgumentException.class, () -> LocalMfiImportEncoding.decodeBase64(ascii(value)));
        }
    }

    @Test public void rejectsUrlSafeUnicodeBomAndOtherNonAlphabetBytes() {
        for (byte[] value : new byte[][] {ascii("-_8="), ascii("Zg!= "), ascii("Zg==#"), ascii("Zg==\0"),
                {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'Z', 'g', '=', '='},
                "Zg==\u00a0".getBytes(StandardCharsets.UTF_8)}) {
            assertThrows(IllegalArgumentException.class, () -> LocalMfiImportEncoding.decodeBase64(value));
        }
    }

    @Test public void enforcesDecodedLimitBeforeAllocatingDecodedBytes() {
        byte[] boundary = new byte[LocalMfiImportEncoding.MAX_FILE_BYTES];
        Arrays.fill(boundary, (byte) 0x80);
        assertArrayEquals(boundary, LocalMfiImportEncoding.decodeBase64(Base64.getEncoder().encode(boundary)));
        byte[] oversized = Base64.getEncoder().encode(new byte[LocalMfiImportEncoding.MAX_FILE_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> LocalMfiImportEncoding.decodeBase64(oversized));
    }

    @Test public void enforcesTotalTextLimitIncludingWhitespace() {
        byte[] boundary = new byte[LocalMfiImportEncoding.MAX_TEXT_BYTES];
        Arrays.fill(boundary, (byte) ' ');
        System.arraycopy(ascii("Zg=="), 0, boundary, 0, 4);
        assertArrayEquals(ascii("f"), LocalMfiImportEncoding.decodeBase64(boundary));
        assertThrows(IllegalArgumentException.class,
                () -> LocalMfiImportEncoding.decodeBase64(Arrays.copyOf(boundary, boundary.length + 1)));
    }

    @Test public void preservesRawPkcs8AndNormalizesWholeFileBase64() throws Exception {
        byte[] original = key();
        assertArrayEquals(original, read(original));
        assertArrayEquals(original, read(Base64.getEncoder().encode(original)));
        byte[] wrapped = Base64.getMimeEncoder(64, ascii("\r\n ")).encode(original);
        assertArrayEquals(original, read(wrapped));
    }

    @Test public void preservesDerAndPemCertificateBytesBeforeAndAfterOuterBase64() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] algorithm = der(0x30, der(6, new byte[] {42, (byte) 134, 72, (byte) 206, 61, 4, 3, 2}));
        byte[] name = new X500Principal("CN=Synthetic MFi import test only").getEncoded();
        byte[] tbs = der(0x30, der(2, new byte[] {1}), algorithm, name,
                der(0x30, der(0x17, ascii("000101000000Z")), der(0x17, ascii("490101000000Z"))),
                name, pair.getPublic().getEncoded());
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate()); signer.update(tbs);
        byte[] certificate = der(0x30, tbs, algorithm, der(3, new byte[] {0}, signer.sign()));
        byte[] pkcs7 = der(0x30, der(6, new byte[] {42, (byte) 134, 72, (byte) 134, (byte) 247, 13, 1, 7, 2}),
                der(0xa0, der(0x30, der(2, new byte[] {1}), der(0x31),
                        der(0x30, der(6, new byte[] {42, (byte) 134, 72, (byte) 134, (byte) 247, 13, 1, 7, 1})),
                        der(0xa0, certificate), der(0x31))));
        for (byte[] original : new byte[][] {certificate, pkcs7, pem("CERTIFICATE", certificate), pem("PKCS7", pkcs7)}) {
            for (byte[] input : new byte[][] {original, Base64.getEncoder().encode(original)}) {
                assertArrayEquals(original, LocalMfiImportEncoding.read(new ByteArrayInputStream(input), false, true, () -> {}));
            }
        }
    }

    private static byte[] pem(String label, byte[] content) {
        return ascii("-----BEGIN " + label + "-----\r\n" +
                Base64.getMimeEncoder(64, ascii("\r\n")).encodeToString(content) +
                "\r\n-----END " + label + "-----\r\n");
    }

    private static byte[] der(int tag, byte[]... chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) body.writeBytes(chunk);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(tag);
        int length = body.size();
        if (length < 128) encoded.write(length);
        else if (length < 256) { encoded.write(0x81); encoded.write(length); }
        else { encoded.write(0x82); encoded.write(length >> 8); encoded.write(length); }
        encoded.writeBytes(body.toByteArray());
        return encoded.toByteArray();
    }

    @Test public void rejectsRecursiveEncodingAndContainersOtherThanWholeFileBase64() throws Exception {
        byte[] encoded = Base64.getEncoder().encode(key());
        assertThrows(IllegalArgumentException.class, () -> read(Base64.getEncoder().encode(encoded)));
        String body = new String(encoded, StandardCharsets.US_ASCII);
        for (String text : new String[] {"data:application/octet-stream;base64," + body,
                "{\"key\":\"" + body + "\"}", "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----"}) {
            assertThrows(IllegalArgumentException.class, () -> read(ascii(text)));
        }
    }

    @Test public void streamReaderAcceptsExactTextBoundary() throws Exception {
        byte[] original = key();
        byte[] text = new byte[LocalMfiImportEncoding.MAX_TEXT_BYTES];
        Arrays.fill(text, (byte) ' ');
        byte[] encoded = Base64.getEncoder().encode(original);
        System.arraycopy(encoded, 0, text, 0, encoded.length);
        assertArrayEquals(original, read(text));
    }

    @Test public void oversizedStreamReadsOnlyOneSentinelByte() {
        AtomicInteger consumed = new AtomicInteger();
        InputStream endless = new InputStream() {
            @Override public int read() { consumed.incrementAndGet(); return 'A'; }
        };
        assertThrows(IllegalArgumentException.class,
                () -> LocalMfiImportEncoding.read(endless, true, true, () -> {}));
        assertEquals(LocalMfiImportEncoding.MAX_TEXT_BYTES + 1, consumed.get());
    }

    @Test public void rawAssetPathKeepsItsOriginalLimitAndDoesNotDecode() throws Exception {
        byte[] encoded = Base64.getEncoder().encode(key());
        assertArrayEquals(encoded, LocalMfiImportEncoding.read(new ByteArrayInputStream(encoded), true, false, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> LocalMfiImportEncoding.read(
                new ByteArrayInputStream(new byte[LocalMfiImportEncoding.MAX_FILE_BYTES + 1]), true, false, () -> {}));
    }

    @Test public void zeroLengthProviderReadsMakeProgressAndStreamRemainsCallerOwned() throws Exception {
        byte[] original = key();
        ByteArrayInputStream provider = new ByteArrayInputStream(Base64.getEncoder().encode(original)) {
            @Override public synchronized int read(byte[] buffer, int start, int length) { return 0; }
            @Override public void close() { throw new AssertionError("Caller owns stream"); }
        };
        assertArrayEquals(original, LocalMfiImportEncoding.read(provider, true, true, () -> {}));
    }

    @Test public void cancellationIsCheckedBeforeAndAfterProviderReads() {
        AtomicInteger reads = new AtomicInteger();
        InputStream provider = new InputStream() {
            @Override public int read() { reads.incrementAndGet(); return 'A'; }
        };
        assertThrows(IllegalStateException.class, () -> LocalMfiImportEncoding.read(provider, true, true,
                () -> { throw new IllegalStateException("Synthetic cancellation"); }));
        assertEquals(0, reads.get());
        assertThrows(IllegalStateException.class, () -> LocalMfiImportEncoding.read(provider, true, true,
                () -> { if (reads.get() > 0) throw new IllegalStateException("Synthetic cancellation"); }));
        assertEquals(1024, reads.get());
    }

    @Test public void providerFailurePropagatesAndEmptyInputIsRejected() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Synthetic provider failure"); }
            @Override public void close() { throw new AssertionError("Caller owns stream"); }
        };
        assertThrows(IOException.class, () -> LocalMfiImportEncoding.read(broken, true, true, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> read(new byte[0]));
    }
}
