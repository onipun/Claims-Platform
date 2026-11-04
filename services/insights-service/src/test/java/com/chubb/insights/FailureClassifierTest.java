package com.chubb.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.insights.event.PoisonEventException;
import com.chubb.insights.event.UnsupportedSchemaException;
import com.chubb.insights.projection.FailureClassifier;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

class FailureClassifierTest {
  @Test
  void databaseOutagesAreRetriedForeverAndNeverPoison() {
    for (Throwable t : new Throwable[] {new CannotGetJdbcConnectionException("x", new SQLException("down")),
        new DataAccessResourceFailureException("x"), new QueryTimeoutException("x"),
        new CannotCreateTransactionException("x")}) {
      Throwable wrapped = new ListenerExecutionFailedException("Listener failed", t);
      assertThat(FailureClassifier.isInfrastructureOutage(wrapped)).as(t.getClass().getSimpleName()).isTrue();
      assertThat(FailureClassifier.isPoison(wrapped)).isFalse();
    }
  }

  @Test
  void badEventsAreNotRetried() {
    for (Throwable t : new Throwable[] {new PoisonEventException("x"), new UnsupportedSchemaException(2, 1)}) {
      Throwable wrapped = new ListenerExecutionFailedException("Listener failed", t);
      assertThat(FailureClassifier.isPoison(wrapped)).isTrue();
      assertThat(FailureClassifier.isInfrastructureOutage(wrapped)).isFalse();
    }
  }

  @Test
  void aDataErrorFromTheEventItselfIsNotAnOutage() {
    assertThat(FailureClassifier.isInfrastructureOutage(new DataIntegrityViolationException("value too long"))).isFalse();
    assertThat(FailureClassifier.isInfrastructureOutage(new IllegalStateException("bug"))).isFalse();
  }
}
