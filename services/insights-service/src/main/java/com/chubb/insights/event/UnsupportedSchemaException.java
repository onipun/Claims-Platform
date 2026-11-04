package com.chubb.insights.event;

/** The event's {@code schemaVersion} is a higher major version than this consumer supports. */
public class UnsupportedSchemaException extends PoisonEventException {
  public UnsupportedSchemaException(int version, int supported) {
    super("unsupported schemaVersion " + version + " (this consumer supports up to " + supported + ")");
  }
}
