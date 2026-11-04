package com.chubb.claims.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401 and 403 from the security filter chain. Same body for a missing, malformed, forged or
 * expired token, so a caller cannot tell which of those it was.
 */
@Component
public class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final ProblemWriter writer;

  public SecurityProblemHandlers(ProblemWriter writer) {
    this.writer = writer;
  }

  @Override
  public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
      throws IOException {
    res.setHeader("WWW-Authenticate", "Bearer");
    writer.write(res, ApiProblem.of(401, "UNAUTHENTICATED", "Unauthorized", "Authentication is required"));
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex)
      throws IOException {
    writer.write(res, ApiProblem.of(403, "FORBIDDEN", "Forbidden", "You do not have access to this resource"));
  }
}
