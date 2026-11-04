package com.chubb.claims.web;

import com.chubb.claims.claim.domain.SubmissionRejectedException;
import com.chubb.claims.claim.domain.DomainException;
import com.chubb.claims.claim.domain.ErrorCode;
import com.chubb.claims.claim.domain.Violation;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartException;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as RFC 9457 problem+json with a stable {@code code}. Nothing from an
 * exception message reaches the caller unless it was written to be shown (DomainException,
 * ApiException); anything unexpected is logged under an id and answered with a generic 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(DomainException.class)
  ResponseEntity<Object> domain(DomainException ex) {
    ErrorCode c = ex.code();
    if (c == ErrorCode.NOT_FOUND) return notFound();
    return respond(ApiProblem.of(c.httpStatus(), c.name(), title(c.httpStatus()), ex.getMessage()));
  }

  @ExceptionHandler(SubmissionRejectedException.class)
  ResponseEntity<Object> rejected(SubmissionRejectedException ex) {
    List<Violation> v = ex.violations();
    ErrorCode first = v.get(0).code();
    List<ApiProblem.FieldProblem> errs = v.stream()
        .map(x -> new ApiProblem.FieldProblem(x.field(), x.code().name(), x.message())).toList();
    return respond(ApiProblem.of(first.httpStatus(), first.name(), title(first.httpStatus()),
        "The claim could not be accepted").withErrors(errs));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Object> api(ApiException ex) {
    return respond(ApiProblem.of(ex.status().value(), ex.code(), ex.status().getReasonPhrase(), ex.getMessage()));
  }

  /**
   * A multipart body that is malformed is the client's fault: 400 and quiet (debug), not a 500 with a stack
   * trace. A client that abandoned the upload is quieter still (debug, nothing written). A cause that is a
   * real I/O failure while reading the body (disk, temp space, the container) is ours: ERROR with an
   * errorId and a 500. Too-large uploads stay 413 (handled by the superclass, the closer match).
   */
  @ExceptionHandler(MultipartException.class)
  ResponseEntity<Object> badMultipart(MultipartException ex, HttpServletResponse response) {
    if (clientGone(ex)) {
      log.debug("Client closed the connection during an upload: {}", ex.toString());
      return null;
    }
    if (isServerSideIoFailure(ex)) {
      String id = UUID.randomUUID().toString();
      log.error("Multipart read failed on the server, errorId={}", id, ex);
      if (committed(response)) return null;
      return respond(ApiProblem.of(500, "INTERNAL_ERROR", title(500), "An unexpected error occurred").withErrorId(id));
    }
    log.debug("Rejected multipart request: {}", ex.toString());
    if (committed(response)) return null;
    return respond(ApiProblem.of(400, "MALFORMED_REQUEST", title(400), "The upload could not be read"));
  }

  /** An IOException in the cause chain that is not the multipart parser reporting a malformed stream. */
  static boolean isServerSideIoFailure(Throwable ex) {
    for (Throwable t = ex.getCause(); t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof java.io.IOException && !t.getClass().getName().contains("fileupload")) return true;
    }
    return false;
  }

  /** A query string Tomcat cannot parse (for example an empty parameter name) is the caller's fault. */
  @ExceptionHandler(InvalidParameterException.class)
  ResponseEntity<Object> badQueryString(InvalidParameterException ex, HttpServletResponse response) {
    log.debug("Rejected unparseable query string: {}", ex.toString());
    if (committed(response)) return null;
    return respond(ApiProblem.of(400, "MALFORMED_REQUEST", title(400), "The query string could not be read"));
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<Object> stale() {
    return respond(ApiProblem.of(409, "STALE_VERSION", "Conflict",
        "The claim was changed by someone else. Reload and try again"));
  }

  @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
  void security(RuntimeException ex) {
    throw ex; // the security filter chain answers these
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception ex, HttpServletResponse response) {
    if (clientGone(ex)) {
      log.debug("Client closed the connection: {}", ex.toString());
      return null;
    }
    String id = UUID.randomUUID().toString();
    log.error("Unhandled error, errorId={}", id, ex);
    if (committed(response)) return null; // the response has started: a second body would corrupt it
    return respond(ApiProblem.of(500, "INTERNAL_ERROR", "Internal Server Error",
        "An unexpected error occurred").withErrorId(id));
  }


  // ---- a client that went away, and a response that has already started ----

  /** True when the failure is the peer closing the connection: nothing can be written and nothing is wrong here. */
  static boolean clientGone(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) return true;
      if (t instanceof java.io.IOException io && io.getMessage() != null) {
        String m = io.getMessage();
        if (m.contains("Broken pipe") || m.contains("Connection reset by peer")) return true;
      }
    }
    return false;
  }

  private static boolean committed(WebRequest request) {
    return request instanceof ServletWebRequest sw && sw.getResponse() != null && sw.getResponse().isCommitted();
  }

  private static boolean committed(HttpServletResponse response) {
    return response != null && response.isCommitted();
  }

  @ExceptionHandler(ClientAbortException.class)
  ResponseEntity<Object> clientAborted(ClientAbortException ex) {
    log.debug("Client closed the connection: {}", ex.toString());
    return null; // handled, nothing written: the connection is gone
  }

  @Override
  protected ResponseEntity<Object> handleAsyncRequestNotUsableException(
      AsyncRequestNotUsableException ex, WebRequest request) {
    log.debug("Client closed the connection during an async response: {}", ex.toString());
    return null;
  }

  // ---- Spring MVC's own exceptions ----

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    List<ApiProblem.FieldProblem> errs = ex.getBindingResult().getAllErrors().stream()
        .map(e -> new ApiProblem.FieldProblem(
            e instanceof FieldError f ? f.getField() : e.getObjectName(), "VALIDATION_FAILED",
            e.getDefaultMessage()))
        .toList();
    return respond(ApiProblem.of(422, "VALIDATION_FAILED", title(422), "The request is not valid")
        .withErrors(errs));
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return respond(ApiProblem.of(422, "VALIDATION_FAILED", title(422), "The request is not valid"));
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return respond(ApiProblem.of(400, "MALFORMED_REQUEST", title(400), "The request body could not be read"));
  }

  /** Everything else Spring raises (404 for unknown paths, 405, 415, missing parameters, type mismatch). */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    int s = statusCode.value();
    if (clientGone(ex)) {
      log.debug("Client closed the connection: {}", ex.toString());
      return null;
    }
    String code = switch (s) {
      case 404 -> "NOT_FOUND";
      case 405 -> "METHOD_NOT_ALLOWED";
      case 406 -> "NOT_ACCEPTABLE";
      case 413 -> "PAYLOAD_TOO_LARGE";
      case 415 -> "UNSUPPORTED_MEDIA_TYPE";
      default -> s >= 500 ? "INTERNAL_ERROR" : "MALFORMED_REQUEST";
    };
    String detail = s == 404 ? "Resource not found" : s == 413 ? "The upload is too large (limit 10 MB)" : s >= 500 ? "An unexpected error occurred"
        : "The request could not be processed";
    ApiProblem problem = ApiProblem.of(s, code, title(s), detail);
    if (s >= 500) {
      // Framework-raised server errors (503 async timeout, unwritable body, missing path variable...)
      // are server faults: log them with an id the caller can quote.
      String id = UUID.randomUUID().toString();
      log.error("Framework error {}, errorId={}", s, id, ex);
      problem = problem.withErrorId(id);
    }
    if (committed(request)) return null; // the response has started: logged above if it was a server fault
    ResponseEntity<Object> r = respond(problem);
    return ResponseEntity.status(r.getStatusCode()).headers(h -> {
      if (headers != null) h.addAll(headers);
      h.setContentType(MediaType.parseMediaType(ApiProblem.MEDIA_TYPE));
    }).body(r.getBody());
  }

  // ---- helpers ----

  static ResponseEntity<Object> notFound() {
    return respond(ApiProblem.of(404, "NOT_FOUND", "Not Found", "Resource not found"));
  }

  private static ResponseEntity<Object> respond(ApiProblem p) {
    return ResponseEntity.status(p.status())
        .contentType(MediaType.parseMediaType(ApiProblem.MEDIA_TYPE)).body(p);
  }

  private static String title(int status) {
    HttpStatus s = HttpStatus.resolve(status);
    return s == null ? "Error" : s.getReasonPhrase();
  }
}
