package com.chubb.claims.identity;

import com.chubb.claims.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Same exception, and so the same response, for an unknown user and a wrong password. It is an
 * ApiException so the web layer needs no knowledge of identity (no web -> identity dependency).
 */
public class InvalidCredentialsException extends ApiException {
  public InvalidCredentialsException() {
    super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
  }
}
