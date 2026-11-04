package com.chubb.claims.document;

import com.chubb.claims.document.DocumentViews.Decision;
import com.chubb.claims.document.DocumentViews.DocumentChecklist;
import com.chubb.claims.document.DocumentViews.DocumentView;
import com.chubb.claims.document.DocumentViews.Download;
import com.chubb.claims.document.DocumentViews.ReviewRequest;
import com.chubb.claims.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Thin controllers for documents. Access decisions and audit rows live in DocumentService. */
final class DocumentControllers {
  private DocumentControllers() {}

  @RestController
  @RequestMapping("/api/v1/claimant/claims/{id}/documents")
  static class Claimant {
    private final DocumentService service;

    Claimant(DocumentService service) {
      this.service = service;
    }

    @GetMapping
    DocumentChecklist checklist(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
      return service.claimantChecklist(AuthenticatedUser.from(jwt), id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentView> upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
        @RequestParam("documentType") String documentType, @RequestParam("file") MultipartFile file) {
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(service.upload(AuthenticatedUser.from(jwt), id, documentType, file));
    }

    @GetMapping("/{docId}/content")
    ResponseEntity<Resource> content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID docId) {
      return stream(service.claimantContent(AuthenticatedUser.from(jwt), id, docId));
    }
  }

  @RestController
  @RequestMapping("/api/v1/staff/claims/{id}/documents")
  static class Staff {
    private final DocumentService service;

    Staff(DocumentService service) {
      this.service = service;
    }

    @GetMapping
    DocumentChecklist checklist(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
      return service.staffChecklist(AuthenticatedUser.from(jwt), id);
    }

    @GetMapping("/{docId}/content")
    ResponseEntity<Resource> content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID docId) {
      return stream(service.staffContent(AuthenticatedUser.from(jwt), id, docId));
    }

    @PostMapping("/{docId}/review")
    DocumentView review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID docId,
        @Valid @RequestBody ReviewRequest body) {
      Decision d = body.decision();
      return service.review(AuthenticatedUser.from(jwt), id, docId, d, body.reason());
    }
  }

  /** Streams from disk (no whole-file buffer). Type comes from the verified magic bytes (T20). */
  static ResponseEntity<Resource> stream(Download d) {
    InputStream in = d.stream();
    long size = d.document().getSizeBytes();
    Resource body = new InputStreamResource(in) {
      @Override public long contentLength() { return size; }
      @Override public String getFilename() { return null; }
    };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(d.document().getContentType()))
        .contentLength(size)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(d.document().getFilename()).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(body);
  }
}
