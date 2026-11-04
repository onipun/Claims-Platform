package com.chubb.claims.claim.domain;

public sealed interface PermissionResult {
  record Allowed() implements PermissionResult {}

  /** Access failure: the caller must see 404 so ids cannot be probed. */
  record NotFound() implements PermissionResult {}

  record Forbidden(ErrorCode code) implements PermissionResult {}

  /** A business rule on the request itself (409 or 422). */
  record Rejected(ErrorCode code) implements PermissionResult {}
}
