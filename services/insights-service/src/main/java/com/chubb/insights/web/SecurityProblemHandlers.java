package com.chubb.insights.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 401 and 403 from the filter chain as problem+json; one body for every kind of bad token. */
@Component
public class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final JsonMapper mapper;

  public SecurityProblemHandlers(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
      throws IOException {
    res.setHeader("WWW-Authenticate", "Bearer");
    write(res, ApiProblem.of(401, "UNAUTHENTICATED", "Unauthorized", "Authentication is required"));
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex)
      throws IOException {
    write(res, ApiProblem.of(403, "FORBIDDEN", "Forbidden", "You do not have access to this resource"));
  }

  private void write(HttpServletResponse res, ApiProblem p) throws IOException {
    res.setStatus(p.status());
    res.setContentType(ApiProblem.MEDIA_TYPE);
    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
    res.getOutputStream().write(mapper.writeValueAsBytes(p));
  }
}
