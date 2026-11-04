package com.chubb.insights.projection;

import com.chubb.insights.event.PoisonEventException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;

/** Decides what a failed record means. The two answers are the only ones that matter. */
public final class FailureClassifier {
  private FailureClassifier() {}

  /** Bad event: retrying cannot help, so it goes to the DLT at once. */
  public static boolean isPoison(Throwable t) {
    return any(t, x -> x instanceof PoisonEventException);
  }

  /**
   * The database (not the event) is the problem: connection lost, pool exhausted, timeout, failover. The event
   * is good and must never reach the DLT because Postgres was down, so it is retried until the database
   * returns; blocking this partition meanwhile is correct. A constraint or data error is NOT here: it is the
   * event's fault and is bounded like any other unexpected failure.
   */
  public static boolean isInfrastructureOutage(Throwable t) {
    return any(t, x -> x instanceof TransientDataAccessException || x instanceof DataAccessResourceFailureException
        || x instanceof RecoverableDataAccessException || x instanceof QueryTimeoutException
        || x instanceof TransactionException);
  }

  private static boolean any(Throwable t, java.util.function.Predicate<Throwable> p) {
    for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) if (p.test(t)) return true;
    return false;
  }
}
