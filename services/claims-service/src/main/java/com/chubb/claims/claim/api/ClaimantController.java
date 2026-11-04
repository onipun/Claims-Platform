package com.chubb.claims.claim.api;

import com.chubb.claims.claim.ClaimSubmissionService;
import com.chubb.claims.claim.ClaimantClaimService;
import com.chubb.claims.claim.ETags;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.web.ApiException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin: parse, call a service, shape the HTTP response. Rules and transactions live in the services. */
@RestController
@RequestMapping("/api/v1/claimant")
class ClaimantController {
  private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,128}");

  private final ClaimSubmissionService submissions;
  private final ClaimantClaimService claims;

  ClaimantController(ClaimSubmissionService submissions, ClaimantClaimService claims) {
    this.submissions = submissions;
    this.claims = claims;
  }

  @GetMapping("/policies")
  List<ClaimantPolicy> policies(@AuthenticationPrincipal Jwt jwt) {
    return claims.policies(AuthenticatedUser.from(jwt));
  }

  @PostMapping("/claims")
  ResponseEntity<ClaimantClaimDetail> submit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody SubmitClaimRequest request) {
    if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
          "Idempotency-Key header is required (1 to 128 visible characters)");
    }
    ClaimSubmissionService.Result r = submissions.submit(AuthenticatedUser.from(jwt), idempotencyKey, request);
    return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .location(URI.create("/api/v1/claimant/claims/" + r.claim().id()))
        .eTag(ETags.of(r.claim().version()))
        .body(r.claim());
  }

  @GetMapping("/claims")
  PageResponse<ClaimantClaimSummary> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return claims.list(AuthenticatedUser.from(jwt), page, size);
  }

  @GetMapping("/claims/{id}")
  ResponseEntity<ClaimantClaimDetail> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    ClaimantClaimDetail c = claims.get(AuthenticatedUser.from(jwt), id);
    return ResponseEntity.ok().eTag(ETags.of(c.version())).body(c);
  }

  @PostMapping("/claims/{id}/info-responses")
  ResponseEntity<ClaimantClaimDetail> answer(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody AnswerInfoRequest request) {
    ClaimantClaimDetail c = claims.answerInfo(AuthenticatedUser.from(jwt), id, ifMatch, request.answer());
    return ResponseEntity.ok().eTag(ETags.of(c.version())).body(c);
  }

  @GetMapping("/updates")
  PageResponse<ClaimantUpdate> updates(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return claims.updates(AuthenticatedUser.from(jwt), page, size);
  }
}
