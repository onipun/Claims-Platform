package com.chubb.insights.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RFC 9457 problem document with a stable {@code code}. Same shape as claims-service. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(String type, String title, int status, String detail, String code, String errorId) {
  public static final String MEDIA_TYPE = "application/problem+json";

  public static ApiProblem of(int status, String code, String title, String detail) {
    return new ApiProblem("urn:chubb-claims:problem:" + code, title, status, detail, code, null);
  }
}
