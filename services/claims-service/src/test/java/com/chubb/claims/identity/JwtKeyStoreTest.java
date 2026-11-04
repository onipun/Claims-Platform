package com.chubb.claims.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trap 16: the key is generated once, kept at mode 600, and survives a restart. */
class JwtKeyStoreTest {

  @Test
  void generatesWithMode600ThenReloadsTheSameKey(@TempDir Path dir) throws Exception {
    Path keyDir = dir.resolve("keys");
    JwtKeyStore first = JwtKeyStore.loadOrCreate(keyDir);
    Path file = keyDir.resolve(JwtKeyStore.PRIVATE_FILE);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");

    JwtKeyStore second = JwtKeyStore.loadOrCreate(keyDir);
    assertThat(second.keyId()).isEqualTo(first.keyId());
    assertThat(second.publicKey()).isEqualTo(first.publicKey());
  }

  @Test
  void widePermissionsAreTightenedOnLoad(@TempDir Path dir) throws Exception {
    JwtKeyStore.loadOrCreate(dir);
    Path file = dir.resolve(JwtKeyStore.PRIVATE_FILE);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    JwtKeyStore.loadOrCreate(dir);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
  }

  @Test
  void publishedJwksNeverContainsPrivateMaterial(@TempDir Path dir) {
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> key = ((java.util.List<java.util.Map<String, Object>>)
        JwtKeyStore.loadOrCreate(dir).publicJwks().get("keys")).get(0);
    assertThat(key).containsKeys("kty", "n", "e").doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
  }
}
