package com.chubb.insights.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;

/** I16: a 5xx raised by the framework is logged under an errorId, and the caller gets the same id. */
class FrameworkErrorLoggingTest {

  @Test
  void aFrameworkRaised5xxIsLoggedWithTheErrorIdThatIsInTheBody() {
    Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    logger.setLevel(Level.INFO);
    try {
      ErrorResponseException cause = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
      ResponseEntity<Object> r = new ApiExceptionHandler()
          .handleExceptionInternal(cause, null, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, null);
      ApiProblem p = (ApiProblem) r.getBody();
      assertThat(r.getStatusCode().value()).isEqualTo(503);
      assertThat(p.errorId()).isNotBlank();
      assertThat(logs.list).hasSize(1);
      assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
      assertThat(logs.list.get(0).getFormattedMessage()).contains(p.errorId());
      assertThat(logs.list.get(0).getThrowableProxy().getClassName()).contains("ErrorResponseException");
    } finally {
      logger.detachAppender(logs);
    }
  }

  @Test
  void a4xxIsNotLoggedAsAnErrorAndCarriesNoErrorId() {
    Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      ResponseEntity<Object> r = new ApiExceptionHandler().handleExceptionInternal(
          new ErrorResponseException(HttpStatus.METHOD_NOT_ALLOWED), null, new HttpHeaders(),
          HttpStatus.METHOD_NOT_ALLOWED, null);
      assertThat(((ApiProblem) r.getBody()).errorId()).isNull();
      assertThat(logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR))).isEmpty();
    } finally {
      logger.detachAppender(logs);
    }
  }

  private static Logger capture(ListAppender<ILoggingEvent> logs) {
    Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    logs.start();
    logger.addAppender(logs);
    logger.setLevel(Level.DEBUG);
    return logger;
  }

  private static MockHttpServletResponse committed() {
    MockHttpServletResponse r = new MockHttpServletResponse();
    r.setCommitted(true);
    return r;
  }

  @Test
  void aClientThatWentAwayIsLoggedAtDebugAndNothingIsWritten() throws Exception {
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Logger logger = capture(logs);
    try {
      ApiExceptionHandler h = new ApiExceptionHandler();
      MockHttpServletResponse response = new MockHttpServletResponse();
      assertThat(h.unexpected(new ClientAbortException(new java.io.IOException("Broken pipe")), response)).isNull();
      assertThat(h.unexpected(new java.io.IOException("Broken pipe"), response)).isNull();
      assertThat(h.unexpected(new org.springframework.web.context.request.async.AsyncRequestNotUsableException("gone"), response))
          .isNull();
      assertThat(h.unexpected(new RuntimeException("wrapped", new java.io.IOException("Connection reset by peer")), response))
          .isNull();
      assertThat(h.handleExceptionInternal(new ClientAbortException("x"), null, new HttpHeaders(),
          HttpStatus.INTERNAL_SERVER_ERROR, new ServletWebRequest(new MockHttpServletRequest(), response))).isNull();
      assertThat(h.handleAsyncRequestNotUsableException(
          new org.springframework.web.context.request.async.AsyncRequestNotUsableException("gone"),
          new ServletWebRequest(new MockHttpServletRequest(), response))).isNull();
      assertThat(response.getContentAsString()).as("nothing was written").isEmpty();
      assertThat(logs.list).isNotEmpty().allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.DEBUG));
    } finally {
      logger.detachAppender(logs);
    }
  }

  @Test
  void anUnexpectedErrorAfterTheResponseStartedIsLoggedWithAnErrorIdAndNothingIsWritten() {
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Logger logger = capture(logs);
    try {
      assertThat(new ApiExceptionHandler().unexpected(new IllegalStateException("late"), committed())).isNull();
      assertThat(logs.list).hasSize(1);
      assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
      assertThat(logs.list.get(0).getFormattedMessage()).contains("errorId=");
      assertThat(logs.list.get(0).getThrowableProxy().getClassName()).contains("IllegalStateException");
    } finally {
      logger.detachAppender(logs);
    }
  }

  @Test
  void aFrameworkErrorAfterTheResponseStartedIsLoggedWithAnErrorIdAndNothingIsWritten() {
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Logger logger = capture(logs);
    try {
      ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest(), committed());
      assertThat(new ApiExceptionHandler().handleExceptionInternal(new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE),
          null, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request)).isNull();
      assertThat(logs.list).hasSize(1);
      assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
      assertThat(logs.list.get(0).getFormattedMessage()).contains("errorId=");
    } finally {
      logger.detachAppender(logs);
    }
  }
}
