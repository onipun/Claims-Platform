package com.chubb.claims.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Files under one directory (APP_DOCUMENTS_DIR). Keys must be generated UUIDs; anything else is
 * refused, so a key can never walk out of the directory. Writes go to a temp file, then move.
 */
@Component
class FilesystemDocumentStore implements DocumentStore {
  private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FilesystemDocumentStore.class);

  private final Path root;

  FilesystemDocumentStore(@Value("${app.documents.dir}") String dir) throws IOException {
    this.root = Path.of(dir).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  @Override
  public long save(String key, InputStream in) throws IOException {
    Path target = resolve(key);
    Path tmp = Files.createTempFile(root, ".upload-", ".tmp");
    try {
      long n = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      return n;
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Override
  public InputStream open(String key) throws IOException {
    try {
      return Files.newInputStream(resolve(key));
    } catch (NoSuchFileException e) {
      throw new IOException("stored object missing", e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      Files.deleteIfExists(resolve(key));
    } catch (IOException | RuntimeException e) {
      // Best effort, but never silent: an orphan holds claimant data nothing points to.
      log.warn("Could not delete stored document {}; it is now an orphan file", key, e);
    }
  }

  private Path resolve(String key) {
    if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("invalid storage key");
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root)) throw new IllegalArgumentException("invalid storage key");
    return p;
  }
}
