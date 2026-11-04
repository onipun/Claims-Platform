/** How old a read-model snapshot may be before it is called stale (plan D13: polling with "as of"). */
export const STALE_AFTER_MS = 2 * 60 * 1000;

export interface Freshness {
  /** True when `asOf` is null/unparseable or older than the threshold. */
  stale: boolean;
  /** Age in whole seconds, or null when unknown. */
  ageSeconds: number | null;
}

/** Pure, so it is tested with a fixed clock. A missing or unparseable `asOf` is stale. */
export function freshness(asOf: string | null | undefined, now: number, thresholdMs = STALE_AFTER_MS): Freshness {
  const t = asOf ? Date.parse(asOf) : NaN;
  if (Number.isNaN(t)) return { stale: true, ageSeconds: null };
  const age = Math.max(0, now - t);
  return { stale: age > thresholdMs, ageSeconds: Math.floor(age / 1000) };
}

export function ageText(seconds: number | null): string {
  if (seconds == null) return 'unknown age';
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  return `${Math.floor(seconds / 3600)} h ago`;
}

/** Failures that mean "insights-service is down", as opposed to a bug or a permission problem. */
export function isUnavailable(status: number): boolean {
  return status === 0 || status === 502 || status === 503 || status === 504;
}

/**
 * Same test on a ProblemError. A non-HTTP error (a bug here) is not an outage. A 401 from insights-service
 * counts as unavailable: after both services restart its token check can fail until the next sign-in, and
 * the plan (6.5) wants "dashboards unavailable", not a sign-out.
 */
export function isUnavailableError(p: { status: number; code: string }): boolean {
  if (p.code === 'CLIENT_ERROR') return false;
  return p.status === 401 || isUnavailable(p.status);
}
