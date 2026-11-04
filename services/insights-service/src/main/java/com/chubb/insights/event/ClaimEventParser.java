package com.chubb.insights.event;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Deserialises and validates a record value. Anything wrong is a {@link PoisonEventException}. */
@Component
public class ClaimEventParser {
  /** Highest schema major version this consumer understands. */
  public static final int SUPPORTED_SCHEMA_VERSION = 1;

  private final JsonMapper mapper = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  public ClaimEvent parse(String value) {
    if (value == null) throw new PoisonEventException("empty record value");
    ClaimEvent e;
    try {
      e = mapper.readValue(value, ClaimEvent.class);
    } catch (JacksonException ex) {
      // Deliberately without the cause: Jackson messages can quote the payload.
      throw new PoisonEventException("malformed event JSON");
    }
    if (e == null || e.schemaVersion() == null) throw new PoisonEventException("missing schemaVersion");
    if (e.schemaVersion() > SUPPORTED_SCHEMA_VERSION) {
      throw new UnsupportedSchemaException(e.schemaVersion(), SUPPORTED_SCHEMA_VERSION);
    }
    var s = e.snapshot();
    if (e.eventId() == null || e.claimId() == null || e.claimVersion() == null || e.claimVersion() < 0
        || e.occurredAt() == null || s == null) {
      throw new PoisonEventException("missing envelope field");
    }
    if (!e.claimId().equals(s.claimId()) || s.claimNumber() == null || s.market() == null || s.line() == null
        || s.claimType() == null || s.status() == null || s.currency() == null || s.reserve() == null
        || s.reserveStatus() == null || s.paid() == null || s.submittedAt() == null || s.pickupDueAt() == null
        || s.decisionDueAt() == null || s.late() == null) {
      throw new PoisonEventException("missing or inconsistent snapshot field");
    }
    return e;
  }
}
