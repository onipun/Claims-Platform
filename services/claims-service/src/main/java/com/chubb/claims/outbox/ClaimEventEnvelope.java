package com.chubb.claims.outbox;

import java.util.UUID;

/** The message on {@code claims.events.v1}. Contract: contracts/events/claim-event.v1.schema.json. */
public record ClaimEventEnvelope(
    UUID eventId, String eventType, int schemaVersion, String occurredAt, UUID claimId, long claimVersion,
    ClaimEventSnapshot snapshot) {
  public static final int SCHEMA_VERSION = 1;
}
