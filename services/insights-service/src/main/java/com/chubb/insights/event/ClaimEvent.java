package com.chubb.insights.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Insights' own reading of {@code claims.events.v1} (contract: contracts/events/claim-event.v1.schema.json).
 * Not shared with claims-service. Unknown fields are ignored so a newer producer cannot break this consumer;
 * a higher major {@code schemaVersion} is refused instead (see {@link ClaimEventParser}). Enum-like values are
 * kept as strings so a new status or type added by a newer producer does not fail deserialisation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimEvent(UUID eventId, String eventType, Integer schemaVersion, Instant occurredAt, UUID claimId,
    Long claimVersion, Snapshot snapshot) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Snapshot(UUID claimId, String claimNumber, String market, String line, String claimType,
      String status, UUID officerId, String officerName, String currency, BigDecimal reserve,
      String reserveStatus, BigDecimal paid, BigDecimal approvedAmount, String rejectionCode, Instant submittedAt,
      Instant firstPickedUpAt, Instant decidedAt, Instant closedAt, Instant pickupDueAt, Instant decisionDueAt,
      Boolean late) {}
}
