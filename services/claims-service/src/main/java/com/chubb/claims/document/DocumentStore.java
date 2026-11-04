package com.chubb.claims.document;

import java.io.IOException;
import java.io.InputStream;

/** Where document bytes live (D15). Callers pass generated keys, never client-supplied names. */
public interface DocumentStore {
  /** Streams {@code in} to storage under {@code key}; returns the number of bytes written. */
  long save(String key, InputStream in) throws IOException;

  InputStream open(String key) throws IOException;

  void delete(String key);
}
