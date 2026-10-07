package com.diplay.networkprobe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Bounded, memory-only identity import. Never logs, opens files, changes trust stores or fetches keys.
 * The caller owns (and must promptly wipe) the input arrays. All temporary PEM/DER arrays owned here
 * are wiped. Provider-owned key objects/PKCS8EncodedKeySpec copies cannot be reliably zeroized in
 * Java; discard this identity at Stop and after cancellation so they become eligible for collection.
 * Production always uses platform trust. Import checking does not replace an actual HTTPS handshake
 * with normal trust, SNI and HTTPS endpoint identification on the fixed hostname.
 */
public final class ProbeTlsIdentity {
    public static final int MAX_CHAIN_BYTES = 64 * 1024;
    public static final int MAX_KEY_BYTES = 16 * 1024;
    public static final int MAX_CERTIFICATES = 8;
    private static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final String ANY_EKU = "2.5.29.37.0";
    private static final byte[] RSA_OID = {42, (byte) 134, 72, (byte) 134, (byte) 247, 13, 1, 1, 1};
    private static final byte[] EC_OID = {42, (byte) 134, 72, (byte) 206, 61, 2, 1};
    private static final byte[] RSA_ALGORITHM = {48, 13, 6, 9, 42, (byte) 134, 72, (byte) 134,
            (byte) 247, 13, 1, 1, 1, 5, 0};
    private final SSLContext context;
    private final X509Certificate[] chain;
    private final String summary;

    /** Fixed codes only: no provider exception, PEM, URI, key or certificate subject is exposed. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        private Failure(String code) { super(code); this.code = code; }
    }

    private ProbeTlsIdentity(SSLContext context, X509Certificate[] chain, String summary) {
        this.context = context;
        this.chain = chain.clone();
        this.summary = summary;
    }

    public static ProbeTlsIdentity read(byte[] fullChainPem, byte[] privateKeyPem) throws Failure {
        return read(fullChainPem, privateKeyPem, ProbePolicy.HOSTNAME);
    }

    public static ProbeTlsIdentity read(byte[] fullChainPem, byte[] privateKeyPem, String expectedHostname) throws Failure {
        requireAllowedHostname(expectedHostname);
        return readWithTrust(fullChainPem, privateKeyPem, expectedHostname, systemTrustManager());
    }

    /** Test seam only. No Activity, service or other production caller may inject trust. */
    static ProbeTlsIdentity readWithTrust(byte[] fullChainPem, byte[] privateKeyPem,
            X509TrustManager trust) throws Failure {
        return readWithTrust(fullChainPem, privateKeyPem, ProbePolicy.HOSTNAME, trust);
    }

    static ProbeTlsIdentity readWithTrust(byte[] fullChainPem, byte[] privateKeyPem,
            String expectedHostname, X509TrustManager trust) throws Failure {
        requireAllowedHostname(expectedHostname);
        requireBounds(fullChainPem, MAX_CHAIN_BYTES, "CHAIN");
        requireBounds(privateKeyPem, MAX_KEY_BYTES, "KEY");
        if (trust == null) throw fail("TRUST_UNAVAILABLE");
        X509Certificate[] chain = parseCertificates(fullChainPem);
        PrivateKey key = parsePrivateKey(privateKeyPem);
        validateChain(chain, trust, expectedHostname);
        String algorithm = validateKey(key);
        if (!algorithm.equals(chain[0].getPublicKey().getAlgorithm())) throw fail("KEY_MISMATCH");
        proveKeyMatch(key, chain[0]);
        SSLContext context = serverContext(key, chain);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        int bits = key instanceof RSAKey ? ((RSAKey) key).getModulus().bitLength()
                : ((ECKey) key).getParams().getOrder().bitLength();
        return new ProbeTlsIdentity(context, chain,
                algorithm + " " + bits + " bits; expires " + format.format(chain[0].getNotAfter()));
    }

    /** Returns an unbound TLS 1.2/1.3 server socket; the owner must immediately register and close it. */
    public SSLServerSocket newServerSocket() throws Failure {
        checkValidity();
        SSLServerSocket socket = null;
        try {
            socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            List<String> allowed = new ArrayList<>();
            for (String protocol : socket.getSupportedProtocols()) {
                if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) allowed.add(protocol);
            }
            if (allowed.isEmpty()) throw fail("TLS_PROTOCOL_UNAVAILABLE");
            socket.setEnabledProtocols(allowed.toArray(new String[0]));
            socket.setUseClientMode(false);
            socket.setNeedClientAuth(false);
            socket.setWantClientAuth(false);
            return socket;
        } catch (Failure failure) {
            closeFailedSocket(socket);
            throw failure;
        } catch (IOException | RuntimeException failure) {
            closeFailedSocket(socket);
            throw fail("TLS_SOCKET_FAILED");
        }
    }

    public void checkValidity() throws Failure { checkDates(chain); }
    public void checkValidity(String expectedHostname) throws Failure {
        requireAllowedHostname(expectedHostname);
        checkValidity();
        if (!supportsHostname(expectedHostname)) throw fail("CERTIFICATE_HOSTNAME");
    }
    public boolean supportsHostname(String expectedHostname) {
        if (!allowedHostname(expectedHostname)) return false;
        try { return matchesHostname(chain[0], expectedHostname); }
        catch (CertificateException | RuntimeException failure) { return false; }
    }
    private static boolean allowedHostname(String hostname) {
        return ProbePolicy.HOSTNAME.equals(hostname) || ProbePolicy.HOTSPOT_HOSTNAME.equals(hostname);
    }
    private static void requireAllowedHostname(String hostname) throws Failure {
        if (!allowedHostname(hostname)) throw fail("IDENTITY_HOST_UNSUPPORTED");
    }
    public String description() { return summary; }

    /** A fresh normal-trust client factory, never the imported server certificate as a trust root. */
    public static SSLSocketFactory defaultClientFactory() throws Failure {
        try {
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, null, null);
            return client.getSocketFactory();
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw fail("TRUST_UNAVAILABLE");
        }
    }

    private static void closeFailedSocket(SSLServerSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException | RuntimeException ignored) { }
    }

    private static X509TrustManager systemTrustManager() throws Failure {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
            }
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw fail("TRUST_UNAVAILABLE");
        }
        throw fail("TRUST_UNAVAILABLE");
    }

    private static void requireBounds(byte[] bytes, int maximum, String kind) throws Failure {
        if (bytes == null || bytes.length == 0) throw fail(kind + "_EMPTY");
        if (bytes.length > maximum) throw fail(kind + "_TOO_LARGE");
    }

    enum PemKind { CERTIFICATE, PRIVATE_KEY, UNKNOWN }

    /** Bounded routing hint only; import must still perform all PEM/DER and identity checks. */
    static PemKind classifyPem(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_CHAIN_BYTES) return PemKind.UNKNOWN;
        PemReader reader = new PemReader(bytes);
        if (reader.starts("-----BEGIN CERTIFICATE-----")) return PemKind.CERTIFICATE;
        if (reader.starts("-----BEGIN PRIVATE KEY-----")
                || reader.starts("-----BEGIN RSA PRIVATE KEY-----")
                || reader.starts("-----BEGIN ENCRYPTED PRIVATE KEY-----")
                || reader.starts("-----BEGIN EC PRIVATE KEY-----")) return PemKind.PRIVATE_KEY;
        return PemKind.UNKNOWN;
    }

    private static X509Certificate[] parseCertificates(byte[] pem) throws Failure {
        PemReader reader = new PemReader(pem);
        List<X509Certificate> certificates = new ArrayList<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            while (reader.hasMore()) {
                if (certificates.size() >= MAX_CERTIFICATES) throw fail("CHAIN_TOO_LONG");
                byte[] der = reader.readBlock("CERTIFICATE");
                try {
                    // Reject trailing DER objects even if a provider would ignore them.
                    DerReader.oneSequence(der);
                    ByteArrayInputStream input = new ByteArrayInputStream(der);
                    X509Certificate certificate = (X509Certificate) factory.generateCertificate(input);
                    if (input.available() != 0) throw fail("CERTIFICATE_MALFORMED");
                    certificates.add(certificate);
                } finally { wipe(der); }
            }
        } catch (Failure failure) {
            throw parseFailure("CERT", failure);
        } catch (CertificateException | RuntimeException failure) {
            throw fail("CERTIFICATE_MALFORMED");
        }
        if (certificates.isEmpty()) throw fail("CHAIN_EMPTY");
        return certificates.toArray(new X509Certificate[0]);
    }

    private static PrivateKey parsePrivateKey(byte[] pem) throws Failure {
        PemReader reader = new PemReader(pem);
        byte[] der = null;
        byte[] wrapped = null;
        try {
            if (reader.starts("-----BEGIN ENCRYPTED PRIVATE KEY-----")) throw fail("KEY_ENCRYPTED");
            if (reader.starts("-----BEGIN EC PRIVATE KEY-----")) throw fail("KEY_SEC1_UNSUPPORTED");
            boolean pkcs1 = reader.starts("-----BEGIN RSA PRIVATE KEY-----");
            if (!pkcs1 && !reader.starts("-----BEGIN PRIVATE KEY-----")) throw fail("KEY_FORMAT_UNSUPPORTED");
            der = reader.readBlock(pkcs1 ? "RSA PRIVATE KEY" : "PRIVATE KEY");
            if (reader.hasMore()) throw fail("KEY_MULTIPLE_BLOCKS");
            if (pkcs1) {
                validateRsaDer(DerReader.oneSequence(der));
                wrapped = wrapRsaPkcs8(der);
            }
            byte[] encoded = pkcs1 ? wrapped : der;
            String algorithm = validatePkcs8(encoded);
            PrivateKey key = KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(encoded));
            validateKey(key);
            return key;
        } catch (Failure failure) {
            throw parseFailure("KEY", failure);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw fail("KEY_MALFORMED");
        } finally {
            wipe(der);
            wipe(wrapped);
        }
    }

    private static String validatePkcs8(byte[] bytes) throws Failure {
        DerReader sequence = DerReader.oneSequence(bytes);
        sequence.requireInteger(0);
        DerReader algorithm = sequence.value(0x30);
        DerReader oid = algorithm.value(0x06);
        final String name;
        DerReader curve = null;
        if (oid.equalsBytes(RSA_OID)) {
            name = "RSA";
            if (algorithm.hasMore() && algorithm.value(0x05).hasMore()) throw fail("KEY_MALFORMED");
        } else if (oid.equalsBytes(EC_OID)) {
            name = "EC";
            // Named curves only; provider validates the OID and EC key point/parameters.
            if (!algorithm.hasMore()) throw fail("KEY_MALFORMED");
            curve = algorithm.value(0x06);
            if (!curve.hasMore()) throw fail("KEY_MALFORMED");
        } else throw fail("KEY_ALGORITHM_UNSUPPORTED");
        algorithm.requireEnd();
        DerReader privateValue = sequence.value(0x04);
        if ("RSA".equals(name)) {
            DerReader rsa = privateValue.value(0x30);
            privateValue.requireEnd();
            validateRsaDer(rsa);
        } else {
            DerReader ec = privateValue.value(0x30);
            privateValue.requireEnd();
            validateEcDer(ec, curve);
        }
        // Only the standard version-zero PrivateKeyInfo. No unparsed attributes/trailing payload.
        sequence.requireEnd();
        return name;
    }

    private static void validateRsaDer(DerReader sequence) throws Failure {
        sequence.requireInteger(0); // Reject multi-prime RSA, including additional OtherPrimeInfos.
        for (int i = 0; i < 8; i++) {
            DerReader integer = sequence.positiveInteger();
            if (i == 0 && integer.positiveBitLength() < 2048) throw fail("KEY_TOO_SMALL");
        }
        sequence.requireEnd();
    }

    private static void validateEcDer(DerReader sequence, DerReader outerCurve) throws Failure {
        sequence.requireInteger(1);
        DerReader scalar = sequence.value(0x04);
        if (!scalar.hasMore()) throw fail("DER_MALFORMED");
        // SEC1 inside PKCS8 only: optional named parameters must agree with the outer algorithm.
        if (sequence.nextTagIs(0xa0)) {
            DerReader parameters = sequence.value(0xa0);
            DerReader curve = parameters.value(0x06);
            parameters.requireEnd();
            if (!curve.equalsValue(outerCurve)) throw fail("KEY_CURVE_MISMATCH");
        }
        if (sequence.nextTagIs(0xa1)) {
            DerReader publicKey = sequence.value(0xa1);
            DerReader bits = publicKey.value(0x03);
            publicKey.requireEnd();
            // EC points are octet-aligned and use compressed or uncompressed SEC1 encoding.
            if (bits.end - bits.offset < 3 || bits.bytes[bits.offset] != 0) throw fail("DER_MALFORMED");
            int pointType = bits.bytes[bits.offset + 1] & 255;
            if (pointType != 2 && pointType != 3 && pointType != 4) throw fail("DER_MALFORMED");
        }
        sequence.requireEnd(); // Reject duplicate/out-of-order fields, trailing objects and data.
    }

    private static String validateKey(PrivateKey key) throws Failure {
        if (key instanceof RSAKey && "RSA".equals(key.getAlgorithm())) {
            if (((RSAKey) key).getModulus().bitLength() < 2048) throw fail("KEY_TOO_SMALL");
            return "RSA";
        }
        if (key instanceof ECPrivateKey && "EC".equals(key.getAlgorithm())) {
            if (((ECKey) key).getParams() == null || ((ECKey) key).getParams().getOrder().bitLength() < 256
                    || ((ECKey) key).getParams().getCurve().getField().getFieldSize() < 256) throw fail("KEY_TOO_SMALL");
            ECPrivateKey ec = (ECPrivateKey) key;
            if (ec.getS().signum() <= 0 || ec.getS().compareTo(ec.getParams().getOrder()) >= 0) throw fail("KEY_MALFORMED");
            return "EC";
        }
        throw fail("KEY_ALGORITHM_UNSUPPORTED");
    }

    private static void checkDates(X509Certificate[] certificates) throws Failure {
        try {
            for (X509Certificate certificate : certificates) certificate.checkValidity();
        } catch (CertificateExpiredException failure) {
            throw fail("CERTIFICATE_EXPIRED");
        } catch (CertificateNotYetValidException failure) {
            throw fail("CERTIFICATE_NOT_YET_VALID");
        } catch (RuntimeException failure) {
            throw fail("CERTIFICATE_MALFORMED");
        }
    }

    private static void validateChain(X509Certificate[] certificates, X509TrustManager trust, String expectedHostname) throws Failure {
        checkDates(certificates);
        X509Certificate leaf = certificates[0];
        try {
            if (leaf.getBasicConstraints() != -1) throw fail("LEAF_IS_CA");
            boolean[] usage = leaf.getKeyUsage();
            if (usage != null && (usage.length == 0 || !usage[0])) throw fail("LEAF_KEY_USAGE");
            List<String> eku = leaf.getExtendedKeyUsage();
            if (eku != null && !eku.contains(SERVER_AUTH) && !eku.contains(ANY_EKU)) throw fail("LEAF_SERVER_AUTH");
            if (!matchesHostname(leaf, expectedHostname)) throw fail("CERTIFICATE_HOSTNAME");
            for (int i = 0; i < certificates.length; i++) {
                if (certificates[i].hasUnsupportedCriticalExtension()) throw fail("CERTIFICATE_CRITICAL_EXTENSION");
                for (int j = 0; j < i; j++) {
                    if (certificates[i].equals(certificates[j])) throw fail("CHAIN_DUPLICATE");
                }
                if (i == 0) continue;
                X509Certificate ca = certificates[i];
                int constraint = ca.getBasicConstraints();
                if (constraint < 0) throw fail("CHAIN_CA_CONSTRAINT");
                boolean[] caUsage = ca.getKeyUsage();
                if (caUsage != null && (caUsage.length <= 5 || !caUsage[5])) throw fail("CHAIN_CA_KEY_USAGE");
                int subordinateCas = 0;
                for (int j = 1; j < i; j++) {
                    if (!isSelfIssued(certificates[j])) subordinateCas++;
                }
                if (constraint < subordinateCas) throw fail("CHAIN_PATH_LENGTH");
                X509Certificate child = certificates[i - 1];
                if (!child.getIssuerX500Principal().equals(ca.getSubjectX500Principal())) throw fail("CHAIN_ORDER");
                try { child.verify(ca.getPublicKey()); }
                catch (GeneralSecurityException failure) { throw fail("CHAIN_SIGNATURE"); }
            }
            String keyAlgorithm = leaf.getPublicKey().getAlgorithm();
            final String authType;
            if ("RSA".equals(keyAlgorithm)) authType = "ECDHE_RSA";
            else if ("EC".equals(keyAlgorithm)) authType = "ECDHE_ECDSA";
            else throw fail("KEY_ALGORITHM_UNSUPPORTED");
            // The authType describes signed TLS 1.2 ECDHE, also appropriate for our digitalSignature
            // policy. This is server authentication, never checkClientTrusted(). The platform owns
            // path/algorithm policy; SAN is checked explicitly above because the 2-arg API omits it.
            try { trust.checkServerTrusted(certificates.clone(), authType); }
            catch (CertificateException failure) { throw fail("CHAIN_UNTRUSTED"); }
            requireCompleteChain(certificates[certificates.length - 1], trust);
        } catch (CertificateException | RuntimeException failure) {
            throw fail("CERTIFICATE_MALFORMED");
        }
    }

    private static boolean isSelfIssued(X509Certificate certificate) {
        return certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal());
    }

    private static void requireCompleteChain(X509Certificate last, X509TrustManager trust) throws Failure {
        // A platform may cache/fetch missing intermediates. Require supplied leaf-first chain to
        // reach a current trust anchor itself so that the server can send a complete chain to peers.
        X509Certificate[] anchors = trust.getAcceptedIssuers();
        if (anchors != null) for (X509Certificate anchor : anchors) {
            if (last.equals(anchor)) return;
            if (last.getIssuerX500Principal().equals(anchor.getSubjectX500Principal())) {
                try { last.verify(anchor.getPublicKey()); return; }
                catch (GeneralSecurityException ignored) { }
            }
        }
        throw fail("CHAIN_INCOMPLETE");
    }

    private static boolean matchesHostname(X509Certificate leaf, String expectedHostname) throws CertificateException {
        Collection<List<?>> names = leaf.getSubjectAlternativeNames();
        if (names == null) return false; // Deliberately no legacy CN fallback.
        String host = expectedHostname.toLowerCase(Locale.ROOT);
        for (List<?> name : names) {
            if (name.size() < 2 || !Integer.valueOf(2).equals(name.get(0)) || !(name.get(1) instanceof String)) continue;
            String dns = ((String) name.get(1)).toLowerCase(Locale.ROOT);
            if (host.equals(dns)) return true;
            if (dns.startsWith("*.") && dns.indexOf('*', 1) < 0) {
                String suffix = dns.substring(1);
                if (host.endsWith(suffix)) {
                    String label = host.substring(0, host.length() - suffix.length());
                    if (!label.isEmpty() && label.indexOf('.') < 0) return true;
                }
            }
        }
        return false;
    }

    private static void proveKeyMatch(PrivateKey key, X509Certificate leaf) throws Failure {
        byte[] challenge = new byte[32];
        byte[] signed = null;
        try {
            SecureRandom random = new SecureRandom();
            random.nextBytes(challenge);
            Signature signature = Signature.getInstance("RSA".equals(key.getAlgorithm()) ? "SHA256withRSA" : "SHA256withECDSA");
            signature.initSign(key, random);
            signature.update(challenge);
            signed = signature.sign();
            signature.initVerify(leaf.getPublicKey());
            signature.update(challenge);
            if (!signature.verify(signed)) throw fail("KEY_MISMATCH");
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw fail("KEY_MISMATCH");
        } finally { wipe(challenge); wipe(signed); }
    }

    private static SSLContext serverContext(PrivateKey key, X509Certificate[] certificates) throws Failure {
        char[] password = new char[32];
        byte[] randomBytes = new byte[32];
        try {
            new SecureRandom().nextBytes(randomBytes);
            for (int i = 0; i < password.length; i++) password[i] = (char) ('!' + (randomBytes[i] & 63));
            KeyStore memory = KeyStore.getInstance(KeyStore.getDefaultType());
            memory.load(null, null);
            memory.setKeyEntry("session", key, password, certificates);
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(memory, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(factory.getKeyManagers(), null, null);
            memory.deleteEntry("session");
            return context;
        } catch (GeneralSecurityException | IOException | RuntimeException failure) {
            throw fail("TLS_IDENTITY_FAILED");
        } finally { Arrays.fill(password, '\0'); wipe(randomBytes); }
    }

    private static byte[] wrapRsaPkcs8(byte[] rsa) throws Failure {
        int contentLength = 3 + RSA_ALGORITHM.length + 1 + lengthBytes(rsa.length) + rsa.length;
        byte[] result = new byte[1 + lengthBytes(contentLength) + contentLength];
        int offset = 0;
        result[offset++] = 0x30;
        offset = putLength(result, offset, contentLength);
        result[offset++] = 0x02; result[offset++] = 0x01; result[offset++] = 0x00;
        System.arraycopy(RSA_ALGORITHM, 0, result, offset, RSA_ALGORITHM.length);
        offset += RSA_ALGORITHM.length;
        result[offset++] = 0x04;
        offset = putLength(result, offset, rsa.length);
        System.arraycopy(rsa, 0, result, offset, rsa.length);
        return result;
    }

    private static int lengthBytes(int length) { return length < 128 ? 1 : length < 256 ? 2 : 3; }
    private static int putLength(byte[] bytes, int offset, int length) {
        if (length < 128) bytes[offset++] = (byte) length;
        else if (length < 256) { bytes[offset++] = (byte) 0x81; bytes[offset++] = (byte) length; }
        else { bytes[offset++] = (byte) 0x82; bytes[offset++] = (byte) (length >>> 8); bytes[offset++] = (byte) length; }
        return offset;
    }

    private static Failure fail(String code) { return new Failure(code); }
    private static Failure parseFailure(String stage, Failure failure) {
        if ("PEM_MALFORMED".equals(failure.code) || "DER_MALFORMED".equals(failure.code)) {
            return fail(stage + "_" + failure.code);
        }
        return failure;
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }

    /** No immutable String of PEM contents, no permissive MIME decoding, no arbitrary preamble. */
    private static final class PemReader {
        private final byte[] input;
        private int offset;
        PemReader(byte[] input) {
            this.input = input;
            // Some phone text editors prepend one UTF-8 BOM. Only byte zero is allowed;
            // a BOM between certificates, after whitespace or inside a block is still invalid.
            if (input.length >= 3 && (input[0] & 255) == 0xef
                    && (input[1] & 255) == 0xbb && (input[2] & 255) == 0xbf) offset = 3;
        }
        boolean hasMore() { skipWhitespace(); return offset < input.length; }
        boolean starts(String text) { skipWhitespace(); return matches(offset, text); }
        private void skipWhitespace() { while (offset < input.length && whitespace(input[offset])) offset++; }
        private static boolean whitespace(byte value) { return value == ' ' || value == '\t' || value == '\r' || value == '\n'; }
        private boolean matches(int index, String text) {
            if (text.length() > input.length - index) return false;
            for (int i = 0; i < text.length(); i++) if (input[index + i] != text.charAt(i)) return false;
            return true;
        }
        byte[] readBlock(String label) throws Failure {
            String begin = "-----BEGIN " + label + "-----";
            String end = "-----END " + label + "-----";
            if (!starts(begin)) throw fail("PEM_MALFORMED");
            offset += begin.length();
            if (offset >= input.length || !whitespace(input[offset])) throw fail("PEM_MALFORMED");
            byte[] base64 = new byte[input.length - offset];
            byte[] compact = null;
            int count = 0;
            try {
                while (offset < input.length && !matches(offset, end)) {
                    if (matches(offset, "Proc-Type:") || matches(offset, "DEK-Info:")) {
                        throw fail(label.endsWith("PRIVATE KEY") ? "KEY_ENCRYPTED" : "PEM_MALFORMED");
                    }
                    byte value = input[offset++];
                    if (whitespace(value)) continue;
                    boolean alphabet = value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                            || value >= '0' && value <= '9' || value == '+' || value == '/' || value == '=';
                    if (!alphabet) throw fail("PEM_MALFORMED");
                    base64[count++] = value;
                }
                if (offset >= input.length || count == 0) throw fail("PEM_MALFORMED");
                validateBase64(base64, count);
                offset += end.length();
                if (offset < input.length && !whitespace(input[offset])) throw fail("PEM_MALFORMED");
                compact = Arrays.copyOf(base64, count);
                try { return Base64.getDecoder().decode(compact); }
                catch (IllegalArgumentException failure) { throw fail("PEM_MALFORMED"); }
            } finally { wipe(base64); wipe(compact); }
        }

        private static void validateBase64(byte[] bytes, int count) throws Failure {
            int body = count;
            while (body > 0 && bytes[body - 1] == '=') body--;
            int padding = count - body;
            int remainder = body % 4;
            // Java's basic decoder also accepts omitted terminal padding. Require exactly the
            // padded shape when any '=' is present, and reject padding within the body.
            if (body == 0 || remainder == 1 || padding > 2
                    || padding != 0 && (count % 4 != 0 || padding != 4 - remainder)) {
                throw fail("PEM_MALFORMED");
            }
            for (int i = 0; i < body; i++) if (bytes[i] == '=') throw fail("PEM_MALFORMED");
            // Unused terminal bits must be zero, so omitted padding represents the exact same
            // canonical bytes. The decoded result still passes all existing strict DER checks.
            int last = base64Value(bytes[body - 1]);
            if (remainder == 2 && (last & 15) != 0 || remainder == 3 && (last & 3) != 0) {
                throw fail("PEM_MALFORMED");
            }
        }

        private static int base64Value(byte value) {
            if (value >= 'A' && value <= 'Z') return value - 'A';
            if (value >= 'a' && value <= 'z') return value - 'a' + 26;
            if (value >= '0' && value <= '9') return value - '0' + 52;
            return value == '+' ? 62 : 63;
        }
    }

    /** Bounded canonical DER slices share backing storage, avoiding private-integer copies. */
    private static final class DerReader {
        private final byte[] bytes;
        private int offset;
        private final int end;
        DerReader(byte[] bytes, int offset, int end) { this.bytes = bytes; this.offset = offset; this.end = end; }
        static DerReader oneSequence(byte[] bytes) throws Failure {
            DerReader input = new DerReader(bytes, 0, bytes.length);
            DerReader sequence = input.value(0x30);
            input.requireEnd();
            return sequence;
        }
        boolean hasMore() { return offset < end; }
        boolean nextTagIs(int tag) { return hasMore() && (bytes[offset] & 255) == tag; }
        boolean equalsValue(DerReader other) {
            if (other == null || end - offset != other.end - other.offset) return false;
            for (int i = 0; i < end - offset; i++) if (bytes[offset + i] != other.bytes[other.offset + i]) return false;
            return true;
        }
        void requireEnd() throws Failure { if (hasMore()) throw fail("DER_MALFORMED"); }
        DerReader value(int tag) throws Failure {
            if (end - offset < 2 || (bytes[offset++] & 255) != tag) throw fail("DER_MALFORMED");
            int length = bytes[offset++] & 255;
            if (length >= 128) {
                int count = length & 127;
                if (count == 0 || count > 3 || end - offset < count || bytes[offset] == 0) throw fail("DER_MALFORMED");
                length = 0;
                for (int i = 0; i < count; i++) length = (length << 8) | (bytes[offset++] & 255);
                if (length < 128) throw fail("DER_MALFORMED");
            }
            if (length > end - offset) throw fail("DER_MALFORMED");
            DerReader result = new DerReader(bytes, offset, offset + length);
            offset += length;
            return result;
        }
        void requireInteger(int expected) throws Failure {
            DerReader integer = value(0x02);
            if (integer.end - integer.offset != 1 || integer.bytes[integer.offset] != expected) throw fail("KEY_VERSION_UNSUPPORTED");
        }
        DerReader positiveInteger() throws Failure {
            DerReader integer = value(0x02);
            int size = integer.end - integer.offset;
            if (size == 0 || integer.bytes[integer.offset] < 0) throw fail("DER_MALFORMED");
            if (integer.bytes[integer.offset] == 0 && (size == 1 || integer.bytes[integer.offset + 1] >= 0)) throw fail("DER_MALFORMED");
            return integer;
        }
        int positiveBitLength() {
            int first = offset;
            if (bytes[first] == 0) first++;
            int top = bytes[first] & 255;
            return (end - first - 1) * 8 + (32 - Integer.numberOfLeadingZeros(top));
        }
        boolean equalsBytes(byte[] other) {
            if (end - offset != other.length) return false;
            for (int i = 0; i < other.length; i++) if (bytes[offset + i] != other[i]) return false;
            return true;
        }
    }
}
