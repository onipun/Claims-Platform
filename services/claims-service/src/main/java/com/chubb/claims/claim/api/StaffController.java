package com.chubb.claims.claim.api;

import com.chubb.claims.claim.ETags;
import com.chubb.claims.claim.StaffClaimService;
import com.chubb.claims.claim.StaffClaimService.Queue;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.web.ApiException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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

/** Thin: parse, call the service, shape the response. Staff DTOs only (D7). */
@RestController
@RequestMapping("/api/v1/staff")
class StaffController {
  private final StaffClaimService staff;

  StaffController(StaffClaimService staff) {
    this.staff = staff;
  }

  @GetMapping("/claims")
  PageResponse<StaffClaimSummary> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "unassigned") String queue,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    Queue q;
    ClaimStatus st;
    try {
      q = Queue.valueOf(queue.toUpperCase(Locale.ROOT));
      st = status == null || status.isBlank() ? null : ClaimStatus.valueOf(status.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY",
          "queue must be unassigned, mine or all; status must be a claim status");
    }
    return staff.list(AuthenticatedUser.from(jwt), q, st, page, size);
  }

  @GetMapping("/claims/{id}")
  ResponseEntity<StaffClaimDetail> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return ok(staff.get(AuthenticatedUser.from(jwt), id));
  }

  @GetMapping("/claims/{id}/financials")
  ResponseEntity<StaffFinancials> financials(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    StaffFinancials f = staff.financials(AuthenticatedUser.from(jwt), id);
    return ResponseEntity.ok().eTag(ETags.of(f.version())).body(f);
  }

  @GetMapping("/officers")
  List<StaffOfficer> officers(@AuthenticationPrincipal Jwt jwt) {
    return staff.officers(AuthenticatedUser.from(jwt));
  }

  @PostMapping("/claims/{id}/pick-up")
  ResponseEntity<StaffClaimDetail> pickUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
    return ok(staff.pickUp(AuthenticatedUser.from(jwt), id, ifMatch));
  }

  @PostMapping("/claims/{id}/assign")
  ResponseEntity<StaffClaimDetail> assign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody StaffRequests.Assign body) {
    return ok(staff.assign(AuthenticatedUser.from(jwt), id, ifMatch, body.officerId()));
  }

  @PostMapping("/claims/{id}/notes")
  ResponseEntity<StaffClaimDetail> note(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody StaffRequests.Note body) {
    return ok(staff.note(AuthenticatedUser.from(jwt), id, ifMatch, body.text()));
  }

  @PostMapping("/claims/{id}/info-requests")
  ResponseEntity<StaffClaimDetail> requestInfo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody StaffRequests.InfoRequestBody body) {
    return ok(staff.requestInfo(AuthenticatedUser.from(jwt), id, ifMatch, body.question()));
  }

  @PostMapping("/claims/{id}/assessment")
  ResponseEntity<StaffClaimDetail> assess(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody StaffRequests.Assessment body) {
    return ok(staff.assess(AuthenticatedUser.from(jwt), id, ifMatch, body.reserve()));
  }

  @PostMapping("/claims/{id}/approval")
  ResponseEntity<StaffClaimDetail> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody StaffRequests.Approval body) {
    return ok(staff.approve(AuthenticatedUser.from(jwt), id, ifMatch, body.amount()));
  }

  @PostMapping("/claims/{id}/rejection")
  ResponseEntity<StaffClaimDetail> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody StaffRequests.Rejection body) {
    return ok(staff.reject(AuthenticatedUser.from(jwt), id, ifMatch, body.reason(), body.explanation()));
  }

  @PostMapping("/claims/{id}/settlement")
  ResponseEntity<StaffClaimDetail> settle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody StaffRequests.Settlement body) {
    return ok(staff.settle(AuthenticatedUser.from(jwt), id, ifMatch, body.amount()));
  }

  private static ResponseEntity<StaffClaimDetail> ok(StaffClaimDetail c) {
    return ResponseEntity.ok().eTag(ETags.of(c.version())).body(c);
  }
}
