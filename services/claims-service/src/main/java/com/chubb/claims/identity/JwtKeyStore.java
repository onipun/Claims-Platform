package com.chubb.claims.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RS256 signing key (trap 16). It lives in a directory on a volume, so a restart does not
 * invalidate every token. On the first start it is generated there, readable by the owner only.
 */
public final class JwtKeyStore {
  private static final Logger log = LoggerFactory.getLogger(JwtKeyStore.class);
  static final String PRIVATE_FILE = "jwt-private.pem";
  private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

  private final RSAKey key;

  private JwtKeyStore(RSAKey key) {
    this.key = key;
  }

  public static JwtKeyStore loadOrCreate(Path dir) {
    try {
      Files.createDirectories(dir);
      Path file = dir.resolve(PRIVATE_FILE);
      if (Files.exists(file)) {
        tightenPermissions(file);
        return new JwtKeyStore(toJwk(read(file)));
      }
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      KeyPair pair = gen.generateKeyPair();
      write(file, pair);
      log.info("Generated a new JWT signing key");
      return new JwtKeyStore(toJwk(pair));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot load or create the JWT signing key", e);
    } catch (GeneralSecurityExceptionWrapper | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Cannot load or create the JWT signing key", e);
    }
  }

  /** Private and public halves; used to sign. */
  public RSAKey signingKey() { return key; }

  public RSAPublicKey publicKey() {
    try {
      return key.toRSAPublicKey();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public String keyId() { return key.getKeyID(); }

  /** Public JWKS document: never contains private parameters. */
  public java.util.Map<String, Object> publicJwks() {
    return new JWKSet(key.toPublicJWK()).toJSONObject(true);
  }

  private static RSAKey toJwk(KeyPair pair) {
    try {
      RSAKey.Builder b = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
          .privateKey(pair.getPrivate()).keyUse(KeyUse.SIGNATURE)
          .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256);
      RSAKey withoutId = b.build();
      return new RSAKey.Builder(withoutId).keyID(withoutId.computeThumbprint().toString()).build();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static KeyPair read(Path file) throws IOException {
    try {
      String pem = Files.readString(file, StandardCharsets.US_ASCII)
          .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s", "");
      KeyFactory kf = KeyFactory.getInstance("RSA");
      RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(
          new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
          new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
      return new KeyPair(pub, priv);
    } catch (java.security.GeneralSecurityException e) {
      throw new GeneralSecurityExceptionWrapper(e);
    }
  }

  private static void write(Path file, KeyPair pair) throws IOException {
    String body = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(pair.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    Path tmp = file.resolveSibling(PRIVATE_FILE + ".tmp");
    Files.deleteIfExists(tmp);
    try {
      // Created with mode 600 from the start, so the key is never readable by others, even briefly.
      Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    } catch (UnsupportedOperationException e) {
      Files.createFile(tmp); // non-POSIX file system (development on Windows)
    }
    Files.writeString(tmp, body, StandardCharsets.US_ASCII);
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file);
    }
  }

  private static void tightenPermissions(Path file) throws IOException {
    try {
      if (!Files.getPosixFilePermissions(file).equals(OWNER_ONLY)) {
        Files.setPosixFilePermissions(file, OWNER_ONLY);
        log.warn("Signing key file permissions were too open and were reset to 600");
      }
    } catch (UnsupportedOperationException ignored) {
      // non-POSIX file system
    }
  }

  private static final class GeneralSecurityExceptionWrapper extends RuntimeException {
    GeneralSecurityExceptionWrapper(Throwable cause) { super(cause); }
  }
}
