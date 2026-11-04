package com.chubb.claims.web;

import org.springframework.http.HttpStatus;

/** A request-level failure with a stable code that is safe to show to the caller. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() { return status; }
  public String code() { return code; }
}
