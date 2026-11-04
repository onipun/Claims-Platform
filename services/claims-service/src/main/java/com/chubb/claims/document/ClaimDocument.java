package com.chubb.claims.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata only. {@code filename} is the sanitised client name, kept for display; the bytes live
 * under {@code storageKey}, which is generated and has no relation to the name (T20).
 */
@Entity
@Table(name = "claim_document")
public class ClaimDocument {
  @Id private UUID id;

  @Column(name = "claim_id")
  private UUID claimId;

  @Column(name = "document_type")
  private String documentType;

  private String filename;

  @Column(name = "content_type")
  private String contentType;

  @Column(name = "size_bytes")
  private long sizeBytes;

  @Column(name = "storage_key")
  private String storageKey;

  @Enumerated(EnumType.STRING)
  private DocumentStatus status;

  @Column(name = "uploader_id")
  private UUID uploaderId;

  @Column(name = "uploaded_at")
  private Instant uploadedAt;

  @Column(name = "review_note")
  private String reviewNote;

  @Column(name = "reviewed_by")
  private UUID reviewedBy;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Version private long version;

  protected ClaimDocument() {}

  ClaimDocument(UUID claimId, String documentType, String filename, String contentType, long sizeBytes,
      String storageKey, UUID uploaderId, Instant at) {
    this.id = UUID.randomUUID();
    this.claimId = claimId;
    this.documentType = documentType;
    this.filename = filename;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.storageKey = storageKey;
    this.status = DocumentStatus.UPLOADED;
    this.uploaderId = uploaderId;
    this.uploadedAt = at;
  }

  void review(DocumentStatus outcome, String note, UUID by, Instant at) {
    this.status = outcome;
    this.reviewNote = note;
    this.reviewedBy = by;
    this.reviewedAt = at;
  }

  public UUID getId() { return id; }
  public UUID getClaimId() { return claimId; }
  public String getDocumentType() { return documentType; }
  public String getFilename() { return filename; }
  public String getContentType() { return contentType; }
  public long getSizeBytes() { return sizeBytes; }
  public String getStorageKey() { return storageKey; }
  public DocumentStatus getStatus() { return status; }
  public Instant getUploadedAt() { return uploadedAt; }
  public String getReviewNote() { return reviewNote; }
  public Instant getReviewedAt() { return reviewedAt; }
}
