package io.github.pix3lpirat3.mfbridge.core.remote;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.core.util.SecureFiles;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;

/**
 * The instance key pair and self-signed certificate. Created on
 * first run as an EC P-256 key and a minimal X.509 v3 certificate encoded by {@link Der} and signed
 * with SHA256withECDSA, then stored owner-only as PEM. The SHA-256 of the DER certificate is the
 * mod's identity.
 *
 * <p>Immutable and safe to share across threads once loaded.
 */
@AnyThread
public final class InstanceIdentity {
    /** Private key file name inside the bridge directory. */
    public static final String KEY_FILE = "instance.key";
    /** Certificate file name inside the bridge directory. */
    public static final String CERT_FILE = "instance.crt";
    /** Certificate lifetime in years. */
    public static final int VALIDITY_YEARS = 20;

    private static final String ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2";
    private static final String COMMON_NAME = "2.5.4.3";
    private static final String SIGNATURE = "SHA256withECDSA";

    private final PrivateKey key;
    private final X509Certificate certificate;
    private final byte[] der;
    private final byte[] sha256;

    private InstanceIdentity(PrivateKey key, X509Certificate certificate) {
        this.key = key;
        this.certificate = certificate;
        try {
            this.der = certificate.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot encode the instance certificate", e);
        }
        this.sha256 = sha256(der);
    }

    /**
     * Loads the identity from {@code bridgeDir}, or creates and stores a new one when it is missing.
     * An unreadable or mismatched pair is replaced with a warning, which changes the fingerprint, so
     * remote devices must pair again.
     *
     * @throws IOException when a new identity cannot be written
     */
    @ClientThread
    public static InstanceIdentity loadOrCreate(Path bridgeDir, SecureRandom random, Logger log) throws IOException {
        Path keyFile = bridgeDir.resolve(KEY_FILE);
        Path certFile = bridgeDir.resolve(CERT_FILE);
        try {
            byte[] keyDer = pem(Files.readString(keyFile, StandardCharsets.US_ASCII), "PRIVATE KEY");
            byte[] certDer = pem(Files.readString(certFile, StandardCharsets.US_ASCII), "CERTIFICATE");
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
            X509Certificate cert = parse(certDer);
            if (matches(key, cert)) {
                return new InstanceIdentity(key, cert);
            }
            log.warn("Instance key and certificate in {} do not match; creating a new identity", bridgeDir);
        } catch (NoSuchFileException e) {
            log.info("Creating the bridge instance identity in {}", bridgeDir);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Replacing unreadable instance identity in {}: {}", bridgeDir, e.toString());
        }
        InstanceIdentity created = generate(random, Instant.now());
        SecureFiles.writeAtomically(keyFile, toPem("PRIVATE KEY", created.key.getEncoded()), true, log);
        SecureFiles.writeAtomically(certFile, toPem("CERTIFICATE", created.der), true, log);
        return created;
    }

    /**
     * Generates a new key pair and certificate without storing them.
     *
     * @param now start of the validity period; truncated to whole seconds
     */
    public static InstanceIdentity generate(SecureRandom random, Instant now) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"), random);
            KeyPair pair = gen.generateKeyPair();
            byte[] suffix = new byte[8];
            random.nextBytes(suffix);
            String cn = "mineflayer-bridge-" + HexFormat.of().formatHex(suffix);
            byte[] serial = new byte[16];
            random.nextBytes(serial);
            // RFC 5280 4.1.2.2: positive and at most 20 octets.
            serial[0] &= 0x7f;
            serial[0] |= 0x01;
            Instant from = now.truncatedTo(ChronoUnit.SECONDS);
            Instant until = from.atZone(ZoneOffset.UTC).plusYears(VALIDITY_YEARS).toInstant();
            byte[] der = certificate(pair, cn, new BigInteger(serial), from, until);
            return new InstanceIdentity(pair.getPrivate(), parse(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC P-256 with SHA256withECDSA is unavailable", e);
        }
    }

    /** Encodes and signs a minimal v3 certificate with the same issuer and subject. */
    static byte[] certificate(KeyPair pair, String commonName, BigInteger serial, Instant notBefore, Instant notAfter)
            throws GeneralSecurityException {
        byte[] algorithm = Der.sequence(Der.oid(ECDSA_WITH_SHA256));
        byte[] name = Der.sequence(Der.set(Der.sequence(Der.oid(COMMON_NAME), Der.utf8(commonName))));
        byte[] tbs = Der.sequence(
                Der.explicit(0, Der.integer(2)),
                Der.integer(serial),
                algorithm,
                name,
                Der.sequence(Der.time(notBefore), Der.time(notAfter)),
                name,
                // X.509 SubjectPublicKeyInfo is exactly what the JDK returns for an EC public key.
                pair.getPublic().getEncoded());
        Signature signer = Signature.getInstance(SIGNATURE);
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        // The JDK emits the DER ECDSA-Sig-Value that RFC 3279 puts in the BIT STRING.
        return Der.sequence(tbs, algorithm, Der.bitString(signer.sign()));
    }

    private static X509Certificate parse(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static boolean matches(PrivateKey key, X509Certificate cert) throws GeneralSecurityException {
        byte[] probe = "mineflayer-bridge identity check".getBytes(StandardCharsets.US_ASCII);
        Signature s = Signature.getInstance(SIGNATURE);
        s.initSign(key);
        s.update(probe);
        byte[] sig = s.sign();
        Signature v = Signature.getInstance(SIGNATURE);
        v.initVerify(cert.getPublicKey());
        v.update(probe);
        return v.verify(sig);
    }

    /** Returns the certificate. */
    public X509Certificate certificate() {
        return certificate;
    }

    /** Returns the DER encoding of the certificate. */
    public byte[] certificateDer() {
        return der.clone();
    }

    /** Returns the SHA-256 of the DER certificate, 32 bytes. */
    public byte[] sha256() {
        return sha256.clone();
    }

    /** Returns the fingerprint as written to {@code endpoint.json}: {@code sha256:<lower-case hex>}. */
    public String fingerprint() {
        return fingerprint(der);
    }

    /**
     * Returns the {@code fp} parameter of a pairing URI: the first 20 bytes of the fingerprint in
     * unpadded RFC 4648 base32, 32 characters.
     */
    public String pairingPrefix() {
        return Encoding.base32(Arrays.copyOf(sha256, 20));
    }

    /** Returns the {@code sha256:<hex>} fingerprint of any DER certificate. */
    public static String fingerprint(byte[] certificateDer) {
        return "sha256:" + HexFormat.of().formatHex(sha256(certificateDer));
    }

    /**
     * Builds a Netty server context with the JDK provider, TLS 1.2 and 1.3.
     *
     * @throws SSLException when the JDK rejects the key or certificate
     */
    public SslContext serverContext() throws SSLException {
        return SslContextBuilder.forServer(key, certificate)
                .sslProvider(SslProvider.JDK)
                .protocols("TLSv1.3", "TLSv1.2")
                .build();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every Java runtime provides SHA-256", e);
        }
    }

    private static byte[] toPem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return ("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] pem(String text, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int from = text.indexOf(begin);
        int to = text.indexOf(end);
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("no " + label + " block");
        }
        return Base64.getMimeDecoder().decode(text.substring(from + begin.length(), to));
    }
}
