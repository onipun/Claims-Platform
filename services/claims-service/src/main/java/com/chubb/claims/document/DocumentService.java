package com.chubb.claims.document;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimActivity;
import com.chubb.claims.claim.ClaimActivityRepository;
import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.domain.Actor;
import com.chubb.claims.claim.domain.ActorPermissions;
import com.chubb.claims.claim.domain.ClaimAction;
import com.chubb.claims.claim.domain.ClaimContext;
import com.chubb.claims.claim.domain.Decision.Visibility;
import com.chubb.claims.claim.domain.DomainException;
import com.chubb.claims.claim.domain.ErrorCode;
import com.chubb.claims.claim.domain.PermissionResult;
import com.chubb.claims.claim.domain.Transitions;
import com.chubb.claims.document.DocumentViews.ChecklistItem;
import com.chubb.claims.document.DocumentViews.Decision;
import com.chubb.claims.document.DocumentViews.DocumentChecklist;
import com.chubb.claims.document.DocumentViews.DocumentView;
import com.chubb.claims.document.DocumentViews.Download;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.reference.DocumentRequirement;
import com.chubb.claims.reference.DocumentRequirementRepository;
import com.chubb.claims.web.ApiException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Documents are not a claim command: they never touch the Claim row, so they do not bump the version
 * and write no outbox event (plan 6.4: they do not affect any published figure). Timeline rows are
 * written directly. Every download writes an INTERNAL activity (T12).
 */
@Service
@Transactional
public class DocumentService {
  static final int MAX_DOCUMENTS_PER_CLAIM = 50;
  static final String OTHER = "OTHER";

  private final ClaimRepository claims;
  private final ClaimActivityRepository activities;
  private final ClaimDocumentRepository documents;
  private final DocumentRequirementRepository requirements;
  private final DocumentStore store;
  private final Clock clock;
  private final int maxDocuments;

  DocumentService(ClaimRepository claims, ClaimActivityRepository activities, ClaimDocumentRepository documents,
      DocumentRequirementRepository requirements, DocumentStore store, Clock clock,
      @org.springframework.beans.factory.annotation.Value("${app.documents.max-per-claim:" + MAX_DOCUMENTS_PER_CLAIM + "}") int maxDocuments) {
    this.claims = claims;
    this.activities = activities;
    this.documents = documents;
    this.requirements = requirements;
    this.store = store;
    this.clock = clock;
    this.maxDocuments = maxDocuments;
  }

  // ---- claimant ----

  @Transactional(readOnly = true)
  public DocumentChecklist claimantChecklist(AuthenticatedUser user, UUID claimId) {
    return checklist(owned(user, claimId), true);
  }

  public DocumentView upload(AuthenticatedUser user, UUID claimId, String documentType, MultipartFile file) {
    Claim claim = claims.lockByIdAndClaimantId(claimId, user.id())
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Claim not found"));
    if (claim.getStatus().isTerminal()) {
      throw new ApiException(HttpStatus.CONFLICT, "CLAIM_CLOSED", "Documents cannot be added to a closed claim");
    }
    if (documentType == null || !allowedTypes(claim).contains(documentType)) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DOCUMENT_TYPE",
          "documentType is not one of this claim's document types");
    }
    if (documents.countByClaimId(claimId) >= maxDocuments) {
      throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_DOCUMENTS", "This claim has reached the document limit");
    }
    String key = UUID.randomUUID().toString();
    try (InputStream raw = file.getInputStream(); BufferedInputStream in = new BufferedInputStream(raw)) {
      in.mark(FileTypes.HEADER_BYTES);
      byte[] head = new byte[FileTypes.HEADER_BYTES];
      int n = in.readNBytes(head, 0, head.length);
      String type = FileTypes.detect(head, n).orElseThrow(() -> new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE_TYPE", "Only PDF, JPEG and PNG files are accepted"));
      in.reset();
      long size = store.save(key, in);
      deleteFileIfRolledBack(key);
      ClaimDocument doc = new ClaimDocument(claimId, documentType,
          FileTypes.displayName(file.getOriginalFilename(), type), type, size, key, user.id(), clock.instant());
      documents.saveAndFlush(doc);
      activity(claim, user.id(), Visibility.INTERNAL, "DOCUMENT_UPLOADED", "Document uploaded: " + documentType);
      return view(doc, true);
    } catch (IOException e) {
      // The multipart body was already read and spooled before this method ran, so a client that
      // aborted or sent a bad body never gets here (MultipartException, answered 400 by the handler).
      // An IOException here is the server's own spool or storage: 5xx, logged with an errorId.
      throw new UncheckedIOException("Storing an uploaded document failed", e);
    }
  }

  /**
   * The file is written before the transaction commits, so it must go if the transaction does not:
   * a failure in the audit save, or in the commit itself, would otherwise leave an orphan.
   */
  private void deleteFileIfRolledBack(String key) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) store.delete(key); // the store logs a failed delete
      }
    });
  }

  public Download claimantContent(AuthenticatedUser user, UUID claimId, UUID docId) {
    Claim claim = owned(user, claimId);
    return download(claim, docId, user.id());
  }

  // ---- staff ----

  @Transactional(readOnly = true)
  public DocumentChecklist staffChecklist(AuthenticatedUser user, UUID claimId) {
    Claim claim = scoped(user, claimId);
    if (user.id().equals(claim.getClaimantId())) {
      throw new DomainException(ErrorCode.CONFLICT_OF_INTEREST, "CONFLICT_OF_INTEREST");
    }
    return checklist(claim, false);
  }

  public Download staffContent(AuthenticatedUser user, UUID claimId, UUID docId) {
    Claim claim = scoped(user, claimId);
    if (user.id().equals(claim.getClaimantId())) {
      throw new DomainException(ErrorCode.CONFLICT_OF_INTEREST, "CONFLICT_OF_INTEREST");
    }
    return download(claim, docId, user.id());
  }

  /** Same who-may-act rule as adding a note: the assigned officer or a manager of the market (404 / 403). */
  public DocumentView review(AuthenticatedUser user, UUID claimId, UUID docId, Decision decision, String reason) {
    Claim claim = claims.lockByIdAndMarketCodeIn(claimId, user.markets())
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Claim not found"));
    Actor actor = new Actor(user.id(), user.role(), user.markets(), Map.of());
    ClaimContext ctx = new ClaimContext(claim.getClaimantId(), claim.getMarketCode(), claim.getAssignedOfficerId());
    switch (ActorPermissions.check(actor, ctx, ClaimAction.ADD_NOTE, null)) {
      case PermissionResult.Allowed a -> { }
      case PermissionResult.NotFound n -> throw new DomainException(ErrorCode.NOT_FOUND, "Not found");
      case PermissionResult.Forbidden f -> throw new DomainException(f.code(), f.code().name());
      case PermissionResult.Rejected r -> throw new DomainException(r.code(), r.code().name());
    }
    // LIFECYCLE.md: review follows the ADD_NOTE row, so SETTLED and REJECTED give 409 ILLEGAL_TRANSITION.
    Transitions.require(claim.getStatus(), ClaimAction.ADD_NOTE);
    ClaimDocument doc = documents.findByIdAndClaimId(docId, claimId)
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Not found"));
    if (doc.getStatus() != DocumentStatus.UPLOADED) {
      // Under the claim lock a second concurrent review lands here: one winner, one activity (R5).
      throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_REVIEWABLE",
          "This document was already reviewed; wait for a new upload");
    }
    String note = reason == null ? null : reason.strip();
    if (decision == Decision.RE_REQUESTED && (note == null || note.isEmpty())) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "A reason is required to re-request");
    }
    Instant now = clock.instant();
    if (decision == Decision.ACCEPTED) {
      doc.review(DocumentStatus.ACCEPTED, note, user.id(), now);
      activity(claim, user.id(), Visibility.CLAIMANT, "DOCUMENT_ACCEPTED", "Document accepted: " + doc.getDocumentType());
    } else {
      doc.review(DocumentStatus.RE_REQUESTED, note, user.id(), now);
      activity(claim, user.id(), Visibility.CLAIMANT, "DOCUMENT_REREQUESTED",
          "Please upload this document again: " + doc.getDocumentType() + ". " + note);
    }
    documents.saveAndFlush(doc);
    return view(doc, false);
  }

  // ---- shared ----

  private Download download(Claim claim, UUID docId, UUID actorId) {
    ClaimDocument doc = documents.findByIdAndClaimId(docId, claim.getId())
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Not found"));
    // The record exists, so a file that cannot be opened is the server's fault (lost or unreadable
    // storage), not a missing resource: 5xx, logged with an errorId by the exception handler.
    InputStream in;
    try {
      in = store.open(doc.getStorageKey());
    } catch (IOException e) {
      throw new UncheckedIOException("Stored document " + doc.getId() + " could not be opened", e);
    }
    try {
      activity(claim, actorId, Visibility.INTERNAL, "DOCUMENT_DOWNLOADED",
          "Document downloaded: " + doc.getDocumentType() + " (" + doc.getId() + ")");
    } catch (RuntimeException e) {
      try { in.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
      throw e;
    }
    return new Download(doc, in);
  }

  private void activity(Claim claim, UUID actorId, Visibility v, String type, String message) {
    activities.save(new ClaimActivity(claim, type, actorId, v, message, claim.getStatus(), claim.getStatus(),
        clock.instant()));
  }

  private Set<String> allowedTypes(Claim c) {
    Set<String> out = new java.util.HashSet<>(requiredTypes(c).stream().map(DocumentRequirement::getDocumentType).toList());
    out.add(OTHER);
    return out;
  }

  private List<DocumentRequirement> requiredTypes(Claim c) {
    return requirements.findByMarketCodeAndLineAndClaimTypeOrderByDocumentType(
        c.getMarketCode(), c.getLine().name(), c.getClaimType().name());
  }

  private DocumentChecklist checklist(Claim claim, boolean forClaimant) {
    List<ClaimDocument> all = documents.findByClaimIdOrderByUploadedAtAscIdAsc(claim.getId());
    Map<String, List<DocumentView>> byType = new LinkedHashMap<>();
    all.forEach(d -> byType.computeIfAbsent(d.getDocumentType(), k -> new ArrayList<>()).add(view(d, forClaimant)));
    List<ChecklistItem> items = new ArrayList<>();
    int outstanding = 0;
    for (DocumentRequirement r : requiredTypes(claim)) {
      List<DocumentView> uploads = byType.remove(r.getDocumentType());
      DocumentView latest = uploads == null ? null : uploads.get(uploads.size() - 1);
      String status = latest == null ? "MISSING" : latest.status().name();
      if (latest == null || latest.status() == DocumentStatus.RE_REQUESTED) outstanding++;
      items.add(new ChecklistItem(r.getDocumentType(), r.isPlaceholder(), r.getSource(), status,
          latest == null ? null : latest.reviewNote(), uploads == null ? List.of() : uploads));
    }
    List<DocumentView> other = byType.values().stream().flatMap(List::stream).toList();
    return new DocumentChecklist(items, other, outstanding);
  }

  /**
   * The claimant view of a document shows the staff note only while the document is RE_REQUESTED, where
   * the note is the reason to upload again. On an ACCEPTED document the note is internal commentary.
   */
  private static DocumentView view(ClaimDocument d, boolean forClaimant) {
    String note = d.getReviewNote();
    if (forClaimant && d.getStatus() != DocumentStatus.RE_REQUESTED) note = null;
    return new DocumentView(d.getId(), d.getDocumentType(), d.getFilename(), d.getContentType(), d.getSizeBytes(),
        d.getStatus(), note, d.getUploadedAt(), d.getReviewedAt());
  }

  private Claim owned(AuthenticatedUser user, UUID claimId) {
    return claims.findByIdAndClaimantId(claimId, user.id())
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Claim not found"));
  }

  private Claim scoped(AuthenticatedUser user, UUID claimId) {
    return claims.findByIdAndMarketCodeIn(claimId, user.markets())
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Claim not found"));
  }
}
