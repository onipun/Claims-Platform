package com.chubb.claims.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * RFC 9457 problem document. Deliberately has no "instance": the body for a claim that belongs to
 * someone else must be byte-identical to the body for a claim that does not exist.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
    String type, String title, int status, String detail, String code, List<FieldProblem> errors,
    String errorId) {

  public record FieldProblem(String field, String code, String message) {}

  public static final String MEDIA_TYPE = "application/problem+json";

  public static ApiProblem of(int status, String code, String title, String detail) {
    return new ApiProblem("urn:chubb-claims:problem:" + code, title, status, detail, code, null, null);
  }

  public ApiProblem withErrors(List<FieldProblem> errs) {
    return new ApiProblem(type, title, status, detail, code, errs, errorId);
  }

  public ApiProblem withErrorId(String id) {
    return new ApiProblem(type, title, status, detail, code, errors, id);
  }
}
