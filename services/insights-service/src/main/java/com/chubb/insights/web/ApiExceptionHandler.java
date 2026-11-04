package com.chubb.insights.web;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MultipartException;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** problem+json for every error; no stack traces or exception messages reach the caller. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
  void security(RuntimeException ex) {
    throw ex; // the security filter chain answers these
  }

  /** A body or Content-Type this API never reads (multipart without a boundary) and a query string Tomcat cannot parse are the caller's fault. */
  @ExceptionHandler({MultipartException.class, InvalidParameterException.class})
  ResponseEntity<Object> malformed(Exception ex, HttpServletResponse response) {
    log.debug("Rejected malformed request: {}", ex.toString());
    if (committed(response)) return null;
    return respond(ApiProblem.of(400, "MALFORMED_REQUEST", "Bad Request", "The request could not be processed"));
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
    ApiProblem p = ApiProblem.of(500, "INTERNAL_ERROR", "Internal Server Error", "An unexpected error occurred");
    return respond(new ApiProblem(p.type(), p.title(), p.status(), p.detail(), p.code(), id));
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
      case 415 -> "UNSUPPORTED_MEDIA_TYPE";
      default -> s >= 500 ? "INTERNAL_ERROR" : "MALFORMED_REQUEST";
    };
    String detail = s == 404 ? "Resource not found" : s >= 500 ? "An unexpected error occurred"
        : "The request could not be processed";
    ApiProblem p = ApiProblem.of(s, code, HttpStatus.valueOf(s).getReasonPhrase(), detail);
    if (s >= 500) {
      // Framework-raised server errors are server faults: log them with an id the caller can quote.
      String id = UUID.randomUUID().toString();
      log.error("Framework error {}, errorId={}", s, id, ex);
      p = new ApiProblem(p.type(), p.title(), p.status(), p.detail(), p.code(), id);
    }
    if (committed(request)) return null; // the response has started: logged above if it was a server fault
    return respond(p);
  }

  private static ResponseEntity<Object> respond(ApiProblem p) {
    return ResponseEntity.status(p.status()).contentType(MediaType.parseMediaType(ApiProblem.MEDIA_TYPE)).body(p);
  }
}
