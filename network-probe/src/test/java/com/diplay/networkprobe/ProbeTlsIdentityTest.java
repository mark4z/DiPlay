package com.diplay.networkprobe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** All identities and certificates are fresh in-memory synthetic fixtures; no embedded/file keys. */
public class ProbeTlsIdentityTest {
    private static final long HOUR = 3_600_000L;
    private static final byte[] RSA_ALGORITHM = sequence(oid("1.2.840.113549.1.1.11"), der(0x05));
    private static final String HOST = "tesla.mark4z.asia";
    private static final String SERVER = "1.3.6.1.5.5.7.3.1";
    private static KeyPair rootKey, intermediateKey, rsaKey, secondRsaKey, ecKey;
    private static X509Certificate root, intermediate, rsaLeaf, ecLeaf;
    private static X509TrustManager trust;
    private static long serial;

    @BeforeClass public static void syntheticFixtures() throws Exception {
        rootKey = rsa(2048);
        intermediateKey = rsa(2048);
        rsaKey = rsa(2048);
        secondRsaKey = rsa(2048);
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        ecKey = ec.generateKeyPair();
        root = cert(rootKey, "Test Root", null, rootKey.getPrivate(), 2, -HOUR, 24 * HOUR, null, 4, null, false);
        intermediate = cert(intermediateKey, "Test Intermediate", root, rootKey.getPrivate(), 1, -HOUR, 12 * HOUR, null, 4, null, false);
        rsaLeaf = leaf(rsaKey, HOST);
        ecLeaf = leaf(ecKey, HOST);
        trust = trust(root);
    }

    @Test public void rsaPkcs8AndPkcs1ProduceUnboundModernTlsSockets() throws Exception {
        for (byte[] key : new byte[][] { pkcs8(rsaKey), pkcs1(rsaKey) }) {
            ProbeTlsIdentity identity = read(chain(rsaLeaf), key);
            identity.checkValidity();
            assertTrue(identity.description().startsWith("RSA 2048 bits; expires "));
            assertTrue(identity.description().endsWith("UTC"));
            assertFalse(identity.description().contains(HOST));
            try (SSLServerSocket socket = identity.newServerSocket()) {
                assertFalse(socket.isBound());
                assertFalse(socket.getNeedClientAuth());
                assertFalse(socket.getWantClientAuth());
                assertFalse(socket.getUseClientMode());
                assertTrue(socket.getEnabledProtocols().length > 0);
                for (String protocol : socket.getEnabledProtocols()) assertTrue(protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3"));
            }
        }
    }

    @Test public void ecPkcs8AndOptionalRootAreSupported() throws Exception {
        ProbeTlsIdentity identity = read(chain(ecLeaf), pkcs8(ecKey));
        assertTrue(identity.description().startsWith("EC 256 bits; expires "));
        read(pemChain(ecLeaf, intermediate, root), pkcs8(ecKey));
        assertNotNull(ProbeTlsIdentity.defaultClientFactory());
    }

    @Test public void hostnameSelectionIsLimitedAndRecheckedForEachMode() throws Exception {
        String hotspot = ProbePolicy.HOTSPOT_HOSTNAME;
        X509Certificate hotspotLeaf = leaf(rsaKey, hotspot);
        ProbeTlsIdentity hotspotIdentity = ProbeTlsIdentity.readWithTrust(chain(hotspotLeaf), pkcs8(rsaKey), hotspot, trust);
        assertTrue(hotspotIdentity.supportsHostname(hotspot));
        assertFalse(hotspotIdentity.supportsHostname(HOST));
        hotspotIdentity.checkValidity(hotspot);
        assertEquals("CERTIFICATE_HOSTNAME", assertThrows(ProbeTlsIdentity.Failure.class,
                () -> hotspotIdentity.checkValidity(HOST)).code);
        failure("CERTIFICATE_HOSTNAME", chain(hotspotLeaf), pkcs8(rsaKey));
        ProbeTlsIdentity wildcard = read(chain(leaf(rsaKey, "*.mark4z.asia")), pkcs8(rsaKey));
        assertTrue(wildcard.supportsHostname(HOST));
        assertTrue(wildcard.supportsHostname(hotspot));
        assertFalse(wildcard.supportsHostname("other.mark4z.asia"));
        assertFalse(wildcard.supportsHostname(null));
        assertEquals("IDENTITY_HOST_UNSUPPORTED", assertThrows(ProbeTlsIdentity.Failure.class,
                () -> ProbeTlsIdentity.readWithTrust(chain(hotspotLeaf), pkcs8(rsaKey), "other.mark4z.asia", trust)).code);
        assertEquals("IDENTITY_HOST_UNSUPPORTED", assertThrows(ProbeTlsIdentity.Failure.class,
                () -> wildcard.checkValidity(null)).code);
    }

    @Test public void privateKeyMustMatchLeafInBothRsaFormats() throws Exception {
        failure("KEY_MISMATCH", chain(rsaLeaf), pkcs8(secondRsaKey));
        failure("KEY_MISMATCH", chain(rsaLeaf), pkcs1(secondRsaKey));
        failure("KEY_MISMATCH", chain(ecLeaf), pkcs8(rsaKey));
    }

    @Test public void rejectsExpiredAndNotYetValidLeafAndCa() throws Exception {
        X509Certificate expired = cert(rsaKey, "Expired", intermediate, intermediateKey.getPrivate(), -1, -3 * HOUR, -HOUR, HOST, 128, SERVER, false);
        failure("CERTIFICATE_EXPIRED", chain(expired), pkcs8(rsaKey));
        X509Certificate future = cert(rsaKey, "Future", intermediate, intermediateKey.getPrivate(), -1, HOUR, 3 * HOUR, HOST, 128, SERVER, false);
        failure("CERTIFICATE_NOT_YET_VALID", chain(future), pkcs8(rsaKey));
        X509Certificate oldCa = cert(intermediateKey, "Test Intermediate", root, rootKey.getPrivate(), 1, -3 * HOUR, -HOUR, null, 4, null, false);
        failure("CERTIFICATE_EXPIRED", pemChain(rsaLeaf, oldCa), pkcs8(rsaKey));
        X509Certificate futureCa = cert(intermediateKey, "Test Intermediate", root, rootKey.getPrivate(), 1, HOUR, 3 * HOUR, null, 4, null, false);
        failure("CERTIFICATE_NOT_YET_VALID", pemChain(rsaLeaf, futureCa), pkcs8(rsaKey));
    }

    @Test public void dnsSanIsRequiredAndWildcardCoversOnlyOneLabel() throws Exception {
        failure("CERTIFICATE_HOSTNAME", chain(leaf(rsaKey, "other.mark4z.asia")), pkcs8(rsaKey));
        X509Certificate cnOnly = cert(rsaKey, HOST, intermediate, intermediateKey.getPrivate(), -1, -HOUR, HOUR, null, 128, SERVER, false);
        failure("CERTIFICATE_HOSTNAME", chain(cnOnly), pkcs8(rsaKey));
        read(chain(leaf(rsaKey, "*.mark4z.asia")), pkcs8(rsaKey));
        read(chain(leaf(rsaKey, "TESLA.MARK4Z.ASIA")), pkcs8(rsaKey));
        failure("CERTIFICATE_HOSTNAME", chain(leaf(rsaKey, "*.asia")), pkcs8(rsaKey));
        failure("CERTIFICATE_HOSTNAME", chain(leaf(rsaKey, "t*.mark4z.asia")), pkcs8(rsaKey));
        failure("CERTIFICATE_HOSTNAME", chain(leaf(rsaKey, "*.*.asia")), pkcs8(rsaKey));
    }

    @Test public void validatesLeafCaStatusAndOptionalKeyPurposeRestrictions() throws Exception {
        failure("LEAF_IS_CA", chain(cert(rsaKey, "CA Leaf", intermediate, intermediateKey.getPrivate(), 0, -HOUR, HOUR, HOST, 132, SERVER, false)), pkcs8(rsaKey));
        failure("LEAF_KEY_USAGE", chain(cert(rsaKey, "Encipher only", intermediate, intermediateKey.getPrivate(), -1, -HOUR, HOUR, HOST, 32, SERVER, false)), pkcs8(rsaKey));
        failure("LEAF_SERVER_AUTH", chain(cert(rsaKey, "Client only", intermediate, intermediateKey.getPrivate(), -1, -HOUR, HOUR, HOST, 128, "1.3.6.1.5.5.7.3.2", false)), pkcs8(rsaKey));
        read(chain(cert(rsaKey, "No KU EKU", intermediate, intermediateKey.getPrivate(), -1, -HOUR, HOUR, HOST, -1, null, false)), pkcs8(rsaKey));
        read(chain(cert(rsaKey, "Any EKU", intermediate, intermediateKey.getPrivate(), -1, -HOUR, HOUR, HOST, 128, "2.5.29.37.0", false)), pkcs8(rsaKey));
    }

    @Test public void rejectsUntrustedAndIncompleteChainsWithoutChangingPlatformTrust() throws Exception {
        failure("CHAIN_UNTRUSTED", pemChain(rsaLeaf), pkcs8(rsaKey));
        ProbeTlsIdentity.Failure production = assertThrows(ProbeTlsIdentity.Failure.class,
                () -> ProbeTlsIdentity.read(chain(rsaLeaf), pkcs8(rsaKey)));
        assertEquals("CHAIN_UNTRUSTED", production.code);
        X509TrustManager cachedIntermediate = trust(root, intermediate);
        ProbeTlsIdentity.readWithTrust(pemChain(rsaLeaf), pkcs8(rsaKey), cachedIntermediate);
        // A provider may build through cached intermediates not present in accepted trust anchors.
        // Delegate validation with the intermediate cached, but publish only the actual root anchor.
        X509TrustManager cachedOnly = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] certificates, String authType) throws java.security.cert.CertificateException {
                cachedIntermediate.checkClientTrusted(certificates, authType);
            }
            public void checkServerTrusted(X509Certificate[] certificates, String authType) throws java.security.cert.CertificateException {
                cachedIntermediate.checkServerTrusted(certificates, authType);
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[] { root }; }
        };
        ProbeTlsIdentity.Failure missing = assertThrows(ProbeTlsIdentity.Failure.class,
                () -> ProbeTlsIdentity.readWithTrust(pemChain(rsaLeaf), pkcs8(rsaKey), cachedOnly));
        assertEquals("CHAIN_INCOMPLETE", missing.code);
    }

    @Test public void rejectsWrongOrderDuplicateIssuerAndSignature() throws Exception {
        failure("LEAF_IS_CA", pemChain(intermediate, rsaLeaf), pkcs8(rsaKey));
        failure("CHAIN_ORDER", pemChain(rsaLeaf, root, intermediate), pkcs8(rsaKey));
        failure("CHAIN_DUPLICATE", pemChain(rsaLeaf, intermediate, intermediate), pkcs8(rsaKey));
        X509Certificate impostor = cert(secondRsaKey, "Test Intermediate", root, rootKey.getPrivate(), 1, -HOUR, HOUR, null, 4, null, false);
        failure("CHAIN_SIGNATURE", pemChain(rsaLeaf, impostor), pkcs8(rsaKey));
    }

    @Test public void validatesCaBasicConstraintsKeyUsageAndPathLength() throws Exception {
        X509Certificate notCa = cert(intermediateKey, "Test Intermediate", root, rootKey.getPrivate(), -1, -HOUR, HOUR, null, 4, null, false);
        failure("CHAIN_CA_CONSTRAINT", pemChain(rsaLeaf, notCa), pkcs8(rsaKey));
        X509Certificate noCertSign = cert(intermediateKey, "Test Intermediate", root, rootKey.getPrivate(), 1, -HOUR, HOUR, null, 128, null, false);
        failure("CHAIN_CA_KEY_USAGE", pemChain(rsaLeaf, noCertSign), pkcs8(rsaKey));
        X509Certificate restrictedRoot = cert(rootKey, "Test Root", null, rootKey.getPrivate(), 0, -HOUR, HOUR, null, 4, null, false);
        failure("CHAIN_PATH_LENGTH", pemChain(rsaLeaf, intermediate, restrictedRoot), pkcs8(rsaKey));
        X509Certificate critical = cert(rsaKey, "Unknown critical", intermediate, intermediateKey.getPrivate(), -1, -HOUR, HOUR, HOST, 128, SERVER, true);
        failure("CERTIFICATE_CRITICAL_EXTENSION", chain(critical), pkcs8(rsaKey));
    }

    @Test public void rejectsEmptyOversizedMultipleAndNonPemInputs() throws Exception {
        failure("CHAIN_EMPTY", new byte[0], pkcs8(rsaKey));
        failure("CHAIN_EMPTY", null, pkcs8(rsaKey));
        failure("KEY_EMPTY", chain(rsaLeaf), null);
        failure("CHAIN_TOO_LARGE", new byte[ProbeTlsIdentity.MAX_CHAIN_BYTES + 1], pkcs8(rsaKey));
        failure("KEY_TOO_LARGE", chain(rsaLeaf), new byte[ProbeTlsIdentity.MAX_KEY_BYTES + 1]);
        X509Certificate[] excessive = new X509Certificate[ProbeTlsIdentity.MAX_CERTIFICATES + 1];
        Arrays.fill(excessive, rsaLeaf);
        failure("CHAIN_TOO_LONG", pemChain(excessive), pkcs8(rsaKey));
        failure("KEY_MULTIPLE_BLOCKS", chain(rsaLeaf), concat(pkcs8(rsaKey), pkcs8(rsaKey)));
        failure("PEM_MALFORMED", concat("preamble".getBytes(StandardCharsets.US_ASCII), chain(rsaLeaf)), pkcs8(rsaKey));
        failure("KEY_FORMAT_UNSUPPORTED", chain(rsaLeaf), "not a key".getBytes(StandardCharsets.US_ASCII));
        byte[] extraCertDer = concat(rsaLeaf.getEncoded(), der(0x05));
        failure("DER_MALFORMED", pem("CERTIFICATE", extraCertDer), pkcs8(rsaKey));
    }

    @Test public void rejectsEncryptedAndUnsupportedKeyContainersWithFixedCodes() throws Exception {
        failure("KEY_ENCRYPTED", chain(rsaLeaf), pem("ENCRYPTED PRIVATE KEY", new byte[] { 1, 2, 3 }));
        String legacy = "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,00000000\nAA==\n-----END RSA PRIVATE KEY-----\n";
        failure("KEY_ENCRYPTED", chain(rsaLeaf), legacy.getBytes(StandardCharsets.US_ASCII));
        failure("KEY_SEC1_UNSUPPORTED", chain(ecLeaf), pem("EC PRIVATE KEY", new byte[] { 1, 2, 3 }));
        byte[] dsaLike = sequence(integer(BigInteger.ZERO), sequence(oid("1.2.840.10040.4.1")), der(0x04, new byte[] { 1 }));
        failure("KEY_ALGORITHM_UNSUPPORTED", chain(rsaLeaf), pem("PRIVATE KEY", dsaLike));
    }

    @Test public void strictPkcs1RejectsTruncatedTrailingNegativeAndNonCanonicalDer() throws Exception {
        byte[] key = rsaDer(rsaKey);
        failure("DER_MALFORMED", chain(rsaLeaf), pem("RSA PRIVATE KEY", Arrays.copyOf(key, key.length - 1)));
        failure("DER_MALFORMED", chain(rsaLeaf), pem("RSA PRIVATE KEY", concat(key, new byte[] { 0 })));
        failure("DER_MALFORMED", chain(rsaLeaf), pem("RSA PRIVATE KEY", new byte[] { 0x30, (byte) 0x80, 0, 0 }));
        failure("DER_MALFORMED", chain(rsaLeaf), pem("RSA PRIVATE KEY", new byte[] { 0x30, (byte) 0x84, 127, -1, -1, -1 }));
        failure("DER_MALFORMED", chain(rsaLeaf), pem("RSA PRIVATE KEY", new byte[] { 0x30, (byte) 0x81, 3, 2, 1, 0 }));
        failure("DER_MALFORMED", chain(rsaLeaf), pem("RSA PRIVATE KEY", sequence(integer(BigInteger.ZERO), der(0x02, new byte[] { (byte) 0x80 }))));
        failure("DER_MALFORMED", chain(rsaLeaf), pem("RSA PRIVATE KEY", sequence(integer(BigInteger.ZERO), der(0x02, new byte[] { 0, 1 }))));
        failure("DER_MALFORMED", chain(rsaLeaf), pem("RSA PRIVATE KEY", sequence(integer(BigInteger.ZERO), der(0x02, new byte[0]))));
        failure("KEY_VERSION_UNSUPPORTED", chain(rsaLeaf), pem("RSA PRIVATE KEY", sequence(integer(BigInteger.ONE), integer(BigInteger.ONE))));
        byte[] oversized = new byte[ProbeTlsIdentity.MAX_KEY_BYTES + 1];
        byte[] header = "-----BEGIN RSA PRIVATE KEY-----\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, oversized, 0, header.length);
        failure("KEY_TOO_LARGE", chain(rsaLeaf), oversized);
    }

    @Test public void rejectsSmallRsaAndMalformedPkcs8() throws Exception {
        KeyPair small = rsa(1024);
        failure("KEY_TOO_SMALL", chain(rsaLeaf), pkcs1(small));
        failure("KEY_TOO_SMALL", chain(rsaLeaf), pkcs8(small));
        byte[] key = rsaKey.getPrivate().getEncoded();
        failure("DER_MALFORMED", chain(rsaLeaf), pem("PRIVATE KEY", Arrays.copyOf(key, key.length - 1)));
        failure("DER_MALFORMED", chain(rsaLeaf), pem("PRIVATE KEY", concat(key, der(0x05))));
        failure("PEM_MALFORMED", chain(rsaLeaf), "-----BEGIN PRIVATE KEY-----\n***\n-----END PRIVATE KEY-----\n".getBytes(StandardCharsets.US_ASCII));
    }

    @Test public void strictEcPkcs8RejectsMalformedInnerSec1() throws Exception {
        byte[] scalar = new byte[32];
        Arrays.fill(scalar, (byte) 1);
        byte[] version = integer(BigInteger.ONE), privateValue = der(0x04, scalar);
        failure("KEY_VERSION_UNSUPPORTED", chain(ecLeaf), ecContainer(sequence(integer(BigInteger.ZERO), privateValue)));
        failure("DER_MALFORMED", chain(ecLeaf), ecContainer(sequence(version, der(0x04))));
        failure("DER_MALFORMED", chain(ecLeaf), ecContainer(concat(sequence(version, privateValue), der(0x05))));
        failure("DER_MALFORMED", chain(ecLeaf), ecContainer(sequence(version, privateValue, der(0x05))));
        failure("KEY_CURVE_MISMATCH", chain(ecLeaf), ecContainer(sequence(version, privateValue, der(0xa0, oid("1.3.132.0.34")))));
        byte[] parameters = der(0xa0, oid("1.2.840.10045.3.1.7"));
        failure("DER_MALFORMED", chain(ecLeaf), ecContainer(sequence(version, privateValue, parameters, parameters)));
        failure("DER_MALFORMED", chain(ecLeaf), ecContainer(sequence(version, privateValue, der(0xa1, der(0x03, new byte[] { 1, 4, 0 })))));
        failure("DER_MALFORMED", chain(ecLeaf), ecContainer(sequence(version, privateValue, der(0xa1, der(0x03, new byte[] { 0, 7, 0 })))));
        failure("KEY_MALFORMED", chain(ecLeaf), ecContainer(sequence(version, der(0x04, new byte[32]))));
    }

    @Test public void boundedMalformedKeyCorpusAlwaysReturnsSafeCodes() throws Exception {
        java.util.Random random = new java.util.Random(20261007L);
        for (int i = 0; i < 128; i++) {
            byte[] bytes = new byte[1 + random.nextInt(256)];
            random.nextBytes(bytes);
            byte[] key = pem(i % 2 == 0 ? "RSA PRIVATE KEY" : "PRIVATE KEY", bytes);
            ProbeTlsIdentity.Failure failure = assertThrows(ProbeTlsIdentity.Failure.class, () -> read(chain(rsaLeaf), key));
            assertTrue(failure.code.matches("[A-Z_]+"));
            assertNull(failure.getCause());
        }
    }

    private static byte[] ecContainer(byte[] inner) {
        return pem("PRIVATE KEY", sequence(integer(BigInteger.ZERO),
                sequence(oid("1.2.840.10045.2.1"), oid("1.2.840.10045.3.1.7")), der(0x04, inner)));
    }

    @Test public void doesNotMutateCallerOwnedInputsAndFailuresHaveNoProviderCause() throws Exception {
        byte[] certificates = chain(rsaLeaf), key = pkcs1(rsaKey);
        byte[] originalCertificates = certificates.clone(), originalKey = key.clone();
        read(certificates, key);
        assertArrayEquals(originalCertificates, certificates);
        assertArrayEquals(originalKey, key);
        ProbeTlsIdentity.Failure failure = assertThrows(ProbeTlsIdentity.Failure.class,
                () -> read(certificates, pkcs1(secondRsaKey)));
        assertEquals("KEY_MISMATCH", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static ProbeTlsIdentity read(byte[] chain, byte[] key) throws Exception {
        return ProbeTlsIdentity.readWithTrust(chain, key, trust);
    }
    private static void failure(String code, byte[] chain, byte[] key) throws Exception {
        ProbeTlsIdentity.Failure result = assertThrows(ProbeTlsIdentity.Failure.class, () -> read(chain, key));
        assertEquals(code, result.code);
        assertEquals(code, result.getMessage());
        assertNull(result.getCause());
    }
    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }
    private static X509Certificate leaf(KeyPair pair, String dns) throws Exception {
        return cert(pair, "Synthetic Leaf", intermediate, intermediateKey.getPrivate(), -1, -HOUR, HOUR, dns, 128, SERVER, false);
    }
    private static byte[] chain(X509Certificate leaf) throws Exception { return pemChain(leaf, intermediate); }
    private static byte[] pemChain(X509Certificate... certificates) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (X509Certificate certificate : certificates) bytes.write(pem("CERTIFICATE", certificate.getEncoded()));
        return bytes.toByteArray();
    }
    private static byte[] pkcs8(KeyPair pair) { return pem("PRIVATE KEY", pair.getPrivate().getEncoded()); }
    private static byte[] pkcs1(KeyPair pair) { return pem("RSA PRIVATE KEY", rsaDer(pair)); }
    private static byte[] rsaDer(KeyPair pair) {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) pair.getPrivate();
        return sequence(integer(BigInteger.ZERO), integer(key.getModulus()), integer(key.getPublicExponent()),
                integer(key.getPrivateExponent()), integer(key.getPrimeP()), integer(key.getPrimeQ()),
                integer(key.getPrimeExponentP()), integer(key.getPrimeExponentQ()), integer(key.getCrtCoefficient()));
    }
    private static byte[] pem(String label, byte[] der) {
        return concat(("-----BEGIN " + label + "-----\n").getBytes(StandardCharsets.US_ASCII),
                Base64.getMimeEncoder(64, new byte[] { '\n' }).encode(der),
                ("\n-----END " + label + "-----\n").getBytes(StandardCharsets.US_ASCII));
    }
    private static X509TrustManager trust(X509Certificate... certificates) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int i = 0; i < certificates.length; i++) store.setCertificateEntry("root" + i, certificates[i]);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        throw new AssertionError("Test JVM has no X509TrustManager");
    }

    /** Minimal test certificate construction using only supported java.security APIs and DER. */
    private static X509Certificate cert(KeyPair subjectKey, String subjectName, X509Certificate issuer,
            PrivateKey issuerKey, int caPath, long fromOffset, long untilOffset, String dns,
            int keyUsage, String eku, boolean unknownCritical) throws Exception {
        byte[] name = new X500Principal("CN=" + subjectName).getEncoded();
        byte[] issuerName = issuer == null ? name : issuer.getSubjectX500Principal().getEncoded();
        ByteArrayOutputStream extensions = new ByteArrayOutputStream();
        byte[] basic = caPath < 0 ? sequence() : sequence(der(0x01, new byte[] { (byte) 0xff }), integer(BigInteger.valueOf(caPath)));
        extensions.write(extension("2.5.29.19", true, basic));
        if (keyUsage >= 0) extensions.write(extension("2.5.29.15", true, der(0x03, new byte[] { 0, (byte) keyUsage })));
        if (eku != null) extensions.write(extension("2.5.29.37", false, sequence(oid(eku))));
        if (dns != null) extensions.write(extension("2.5.29.17", false, sequence(der(0x82, dns.getBytes(StandardCharsets.US_ASCII)))));
        if (unknownCritical) extensions.write(extension("1.2.3.4.5.6.7", true, integer(BigInteger.ONE)));
        long now = System.currentTimeMillis();
        byte[] tbs = sequence(der(0xa0, integer(BigInteger.valueOf(2))), integer(BigInteger.valueOf(++serial)),
                RSA_ALGORITHM, issuerName, sequence(time(new Date(now + fromOffset)), time(new Date(now + untilOffset))),
                name, subjectKey.getPublic().getEncoded(), der(0xa3, sequence(extensions.toByteArray())));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(issuerKey);
        signer.update(tbs);
        byte[] encoded = sequence(tbs, RSA_ALGORITHM, der(0x03, concat(new byte[] { 0 }, signer.sign())));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
    }
    private static byte[] extension(String oid, boolean critical, byte[] value) {
        return critical ? sequence(oid(oid), der(0x01, new byte[] { (byte) 0xff }), der(0x04, value))
                : sequence(oid(oid), der(0x04, value));
    }
    private static byte[] time(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(0x18, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] integer(BigInteger value) { return der(0x02, value.toByteArray()); }
    private static byte[] sequence(byte[]... values) { return der(0x30, concat(values)); }
    private static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            byte[] encoded = new byte[10];
            int cursor = encoded.length;
            encoded[--cursor] = (byte) (value & 127);
            while ((value >>>= 7) > 0) encoded[--cursor] = (byte) (128 | (value & 127));
            bytes.write(encoded, cursor, encoded.length - cursor);
        }
        return der(0x06, bytes.toByteArray());
    }
    private static byte[] der(int tag, byte[]... values) {
        byte[] body = concat(values);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(tag);
        if (body.length < 128) bytes.write(body.length);
        else if (body.length < 256) { bytes.write(0x81); bytes.write(body.length); }
        else { bytes.write(0x82); bytes.write(body.length >>> 8); bytes.write(body.length); }
        bytes.write(body, 0, body.length);
        return bytes.toByteArray();
    }
    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] value : values) bytes.write(value, 0, value.length);
        return bytes.toByteArray();
    }
}
