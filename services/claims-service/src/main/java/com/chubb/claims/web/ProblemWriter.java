package com.chubb.claims.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes problem+json from servlet filters, where controller advice does not run. */
@Component
public class ProblemWriter {
  private final JsonMapper mapper;

  public ProblemWriter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  public void write(HttpServletResponse response, ApiProblem problem) throws IOException {
    response.setStatus(problem.status());
    response.setContentType(ApiProblem.MEDIA_TYPE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getOutputStream().write(mapper.writeValueAsBytes(problem));
  }
}
