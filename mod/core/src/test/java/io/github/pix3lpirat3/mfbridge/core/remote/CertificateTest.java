package io.github.pix3lpirat3.mfbridge.core.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class CertificateTest {
    @TempDir
    Path dir;

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    @Test
    void lengthsUseShortAndLongForms() {
        assertEquals("0400", hex(Der.tlv(0x04, new byte[0])));
        byte[] short127 = Der.tlv(0x04, new byte[127]);
        assertEquals("047f", hex(Arrays.copyOf(short127, 2)));
        byte[] long128 = Der.tlv(0x04, new byte[128]);
        assertEquals("048180", hex(Arrays.copyOf(long128, 3)));
        assertEquals(131, long128.length);
        byte[] long300 = Der.tlv(0x04, new byte[300]);
        assertEquals("0482012c", hex(Arrays.copyOf(long300, 4)));
        byte[] long70000 = Der.tlv(0x04, new byte[70_000]);
        assertEquals("0483011170", hex(Arrays.copyOf(long70000, 5)));
    }

    @Test
    void primitivesMatchKnownEncodings() {
        assertEquals("06072a8648ce3d0201", hex(Der.oid("1.2.840.10045.2.1")));
        assertEquals("06082a8648ce3d040302", hex(Der.oid("1.2.840.10045.4.3.2")));
        assertEquals("0603550403", hex(Der.oid("2.5.4.3")));
        assertEquals("020100", hex(Der.integer(0)));
        assertEquals("02017f", hex(Der.integer(127)));
        assertEquals("02020080", hex(Der.integer(128)));
        assertEquals("020102", hex(Der.integer(2)));
        assertEquals("0c03616263", hex(Der.utf8("abc")));
        assertEquals("030200ff", hex(Der.bitString(new byte[] {(byte) 0xff})));
        assertEquals("3006020101020102", hex(Der.sequence(Der.integer(1), Der.integer(2))));
        assertEquals("a003020102", hex(Der.explicit(0, Der.integer(2))));
        assertThrows(IllegalArgumentException.class, () -> Der.oid("1"));
        assertThrows(IllegalArgumentException.class, () -> Der.oid("1.40"));
    }

    @Test
    void timesSwitchToGeneralizedTimeIn2050() {
        Instant y2046 = Instant.parse("2046-03-04T05:06:07Z");
        assertEquals("170d3436303330343035303630375a", hex(Der.time(y2046)));
        Instant y2050 = Instant.parse("2050-01-01T00:00:00Z");
        byte[] g = Der.time(y2050);
        assertEquals(Der.GENERALIZED_TIME, g[0]);
        assertEquals("20500101000000Z", new String(g, 2, g.length - 2, StandardCharsets.US_ASCII));
    }

    @Test
    void base32FollowsRfc4648() {
        assertEquals("", Encoding.base32(new byte[0]));
        assertEquals("MY", Encoding.base32("f".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXQ", Encoding.base32("fo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6", Encoding.base32("foo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6YQ", Encoding.base32("foob".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6YTB", Encoding.base32("fooba".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6YTBOI", Encoding.base32("foobar".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void generatedCertificateParsesAndVerifies() throws Exception {
        Instant now = Instant.parse("2026-11-06T12:00:00.750Z");
        InstanceIdentity id = InstanceIdentity.generate(new SecureRandom(), now);
        X509Certificate cert = id.certificate();
        assertEquals(3, cert.getVersion());
        assertEquals("SHA256withECDSA", cert.getSigAlgName());
        assertEquals("1.2.840.10045.4.3.2", cert.getSigAlgOID());
        X500Principal subject = cert.getSubjectX500Principal();
        assertEquals(subject, cert.getIssuerX500Principal());
        assertTrue(subject.getName().matches("CN=mineflayer-bridge-[0-9a-f]{16}"), subject.getName());
        assertEquals(Instant.parse("2026-11-06T12:00:00Z"), cert.getNotBefore().toInstant());
        assertEquals(now.atZone(ZoneOffset.UTC).plusYears(20).toInstant().getEpochSecond(),
                cert.getNotAfter().toInstant().getEpochSecond());
        assertTrue(cert.getSerialNumber().signum() > 0);
        assertTrue(cert.getSerialNumber().bitLength() <= 127);
        PublicKey key = cert.getPublicKey();
        ECPublicKey ec = (ECPublicKey) key;
        assertEquals(256, ec.getParams().getOrder().bitLength());
        cert.verify(key);
        cert.checkValidity(java.util.Date.from(now.plusSeconds(86_400L * 365 * 19)));
    }

    @Test
    void tamperedCertificateFailsVerification() throws Exception {
        InstanceIdentity id = InstanceIdentity.generate(new SecureRandom(), Instant.now());
        byte[] der = id.certificateDer();
        String cn = id.certificate().getSubjectX500Principal().getName().substring(3);
        int at = indexOf(der, cn.getBytes(StandardCharsets.UTF_8));
        assertTrue(at > 0);
        der[at + cn.length() - 1] ^= 0x01;
        X509Certificate tampered = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        assertThrows(java.security.SignatureException.class, () -> tampered.verify(id.certificate().getPublicKey()));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Test
    void fingerprintIsSha256OfDer() throws Exception {
        InstanceIdentity id = InstanceIdentity.generate(new SecureRandom(), Instant.now());
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(id.certificate().getEncoded());
        assertEquals("sha256:" + hex(sha), id.fingerprint());
        assertArrayEquals(sha, id.sha256());
        assertEquals(Encoding.base32(Arrays.copyOf(sha, 20)), id.pairingPrefix());
        assertEquals(32, id.pairingPrefix().length());
    }

    @Test
    void certificateEncodesSerialAndValidity() throws Exception {
        InstanceIdentity id = InstanceIdentity.generate(new SecureRandom(), Instant.now());
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("EC");
        gen.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair pair = gen.generateKeyPair();
        byte[] der = InstanceIdentity.certificate(pair, "x", BigInteger.valueOf(0x80),
                Instant.parse("2049-12-31T23:59:59Z"), Instant.parse("2069-12-31T23:59:59Z"));
        X509Certificate cert = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        assertEquals(BigInteger.valueOf(0x80), cert.getSerialNumber());
        assertEquals(Instant.parse("2069-12-31T23:59:59Z"), cert.getNotAfter().toInstant());
        cert.verify(pair.getPublic());
        assertNotEquals(id.fingerprint(), InstanceIdentity.fingerprint(der));
    }

    @Test
    void identityIsStoredAndReloaded() throws Exception {
        var log = LoggerFactory.getLogger("cert-test");
        InstanceIdentity first = InstanceIdentity.loadOrCreate(dir, new SecureRandom(), log);
        assertTrue(Files.readString(dir.resolve(InstanceIdentity.KEY_FILE)).startsWith("-----BEGIN PRIVATE KEY-----"));
        assertTrue(Files.readString(dir.resolve(InstanceIdentity.CERT_FILE)).startsWith("-----BEGIN CERTIFICATE-----"));
        InstanceIdentity again = InstanceIdentity.loadOrCreate(dir, new SecureRandom(), log);
        assertEquals(first.fingerprint(), again.fingerprint());

        Files.writeString(dir.resolve(InstanceIdentity.CERT_FILE), "garbage");
        InstanceIdentity replaced = InstanceIdentity.loadOrCreate(dir, new SecureRandom(), log);
        assertNotEquals(first.fingerprint(), replaced.fingerprint());
        assertEquals(replaced.fingerprint(),
                InstanceIdentity.loadOrCreate(dir, new SecureRandom(), log).fingerprint());
    }

    @Test
    void pairingUrisAndCodes() {
        String code = PairingUris.newCode(new SecureRandom());
        assertTrue(code.matches("[A-Z2-7]{8}"), code);
        assertEquals("mfb://192.168.1.20:25590?fp=ABC&code=XYZ", PairingUris.uri("192.168.1.20", 25590, "ABC", "XYZ"));
        assertEquals("mfb://[fe80::1]:1?fp=A&code=B", PairingUris.uri("fe80::1", 1, "A", "B"));
        assertEquals("host.lan", PairingUris.defaultHost(" host.lan ", "0.0.0.0"));
        assertEquals("10.0.0.5", PairingUris.defaultHost(null, "10.0.0.5"));
        assertTrue(PairingUris.defaultHost(null, "0.0.0.0").matches("\\d+\\.\\d+\\.\\d+\\.\\d+"));
        assertEquals("ABCD2345", PairingUris.normalize(" abcd2345 "));
    }
}
