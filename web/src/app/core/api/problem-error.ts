import { HttpErrorResponse } from '@angular/common/http';
import type { ClaimsSchemas } from './index';

type ProblemBody = ClaimsSchemas['Problem'];
type FieldProblem = ClaimsSchemas['FieldProblem'];

/**
 * An API failure, normalised. The backend answers errors as application/problem+json with a stable
 * machine `code`, a human `title`/`detail`, an `errorId` for support, and per-field `errors`.
 * Anything that is not a problem document (network down, proxy 502 HTML) still yields a ProblemError,
 * with code NETWORK or CLIENT_ERROR, so callers have exactly one error shape to handle.
 */
/** Code of a ProblemError built from a non-HTTP error (a bug in this app, not the network or the server). */
export const CLIENT_ERROR = 'CLIENT_ERROR';

/** The server answered 2xx with no body where one was required. */
export const EMPTY_RESPONSE = 'EMPTY_RESPONSE';

export class ProblemError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string | null;
  readonly errorId: string | null;
  readonly fieldErrors: readonly FieldProblem[];

  constructor(init: {
    status: number;
    code: string;
    title: string;
    detail?: string | null;
    errorId?: string | null;
    fieldErrors?: readonly FieldProblem[];
    /** Overrides `message` (kept from a non-HTTP error). */
    message?: string;
    cause?: unknown;
  }) {
    super(init.message ?? init.detail ?? init.title, init.cause === undefined ? undefined : { cause: init.cause });
    this.name = 'ProblemError';
    this.status = init.status;
    this.code = init.code;
    this.title = init.title;
    this.detail = init.detail ?? null;
    this.errorId = init.errorId ?? null;
    this.fieldErrors = init.fieldErrors ?? [];
  }

  /** First message for a form field, or null. */
  fieldMessage(field: string): string | null {
    return this.fieldErrors.find((e) => e.field === field)?.message ?? null;
  }

  /** Messages keyed by field name (first message wins). */
  get fieldMessages(): Record<string, string> {
    const out: Record<string, string> = {};
    for (const e of this.fieldErrors) {
      if (e.field && e.message && !(e.field in out)) out[e.field] = e.message;
    }
    return out;
  }

  static from(err: unknown): ProblemError {
    if (err instanceof ProblemError) return err;
    if (err instanceof HttpErrorResponse) return ProblemError.fromResponse(err.status, err.error);
    // Not an HTTP failure: a programming error (or a throw from a callback). It is not an outage, so it
    // gets its own code, is logged, and keeps the original message and cause.
    console.error('Unexpected non-HTTP error', err);
    return new ProblemError({
      status: 0,
      code: CLIENT_ERROR,
      title: 'Something went wrong',
      message: err instanceof Error ? err.message : String(err),
      cause: err,
    });
  }

  static fromResponse(status: number, body: unknown): ProblemError {
    if (status === 0) {
      return new ProblemError({
        status,
        code: 'NETWORK',
        title: 'Cannot reach the server',
        detail: 'Check your connection and try again.',
      });
    }
    const p = ProblemError.asProblem(body);
    if (p) {
      return new ProblemError({
        status: p.status ?? status,
        code: p.code ?? `HTTP_${status}`,
        title: p.title ?? 'Request failed',
        detail: p.detail ?? null,
        errorId: p.errorId ?? null,
        fieldErrors: Array.isArray(p.errors) ? p.errors : [],
      });
    }
    return new ProblemError({ status, code: `HTTP_${status}`, title: 'Request failed' });
  }

  private static asProblem(body: unknown): ProblemBody | null {
    let value = body;
    if (typeof value === 'string') {
      try {
        value = JSON.parse(value);
      } catch {
        return null;
      }
    }
    return value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as ProblemBody) : null;
  }
}
