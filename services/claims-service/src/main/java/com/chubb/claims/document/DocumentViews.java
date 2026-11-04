package com.chubb.claims.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response shapes shared by the claimant and staff document endpoints. */
public final class DocumentViews {
  private DocumentViews() {}

  public record DocumentView(
      UUID id, String documentType, String filename, String contentType, long sizeBytes, DocumentStatus status,
      String reviewNote, Instant uploadedAt, Instant reviewedAt) {}

  /**
   * One required document. {@code status} is MISSING until something is uploaded, then the status of
   * the latest upload. {@code placeholder} is true when the requirement is not sourced for this market.
   */
  public record ChecklistItem(
      String documentType, boolean placeholder, String source, String status, String reviewNote,
      List<DocumentView> uploads) {}

  /** {@code other} holds uploads of types that are not on the checklist. */
  public record DocumentChecklist(List<ChecklistItem> items, List<DocumentView> other, int outstanding) {}

  public record ReviewRequest(@jakarta.validation.constraints.NotNull Decision decision,
      @com.chubb.claims.web.PlainText @jakarta.validation.constraints.Size(max = 2000) String reason) {}

  public enum Decision {
    ACCEPTED,
    @com.fasterxml.jackson.annotation.JsonAlias("REREQUESTED") RE_REQUESTED
  }

  /** A download in progress: the metadata and an open stream. The caller closes the stream. */
  public record Download(ClaimDocument document, java.io.InputStream stream) {}
}
