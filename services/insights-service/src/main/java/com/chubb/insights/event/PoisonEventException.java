package com.chubb.insights.event;

/**
 * An event that can never be applied, however often it is retried: malformed JSON, a missing required field,
 * or a schema major version this build does not understand. Goes straight to the DLT. The message never
 * carries payload content.
 */
public class PoisonEventException extends RuntimeException {
  public PoisonEventException(String message) {
    super(message, null, false, false);
  }
}
