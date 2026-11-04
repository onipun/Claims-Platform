import { Signal, computed, signal } from '@angular/core';
import { ProblemError } from '../../core/api/problem-error';
import { requestGuard } from '../../core/util/latest';
import { freshness, isUnavailableError, type Freshness } from './freshness';

export type NowFn = () => number;

/**
 * One read-model resource: the last good snapshot is kept when a refresh fails, and the feed says
 * whether it is stale (old or null `asOf`) or unavailable (502/503/504/network). Claim screens never
 * depend on this, so a failure here cannot block claim work.
 */
export class Feed<T> {
  readonly data = signal<T | null>(null);
  readonly loading = signal(false);
  /** A failure that is not "service down" (for example 403 or 500). */
  readonly error = signal<ProblemError | null>(null);
  readonly unavailable = signal(false);
  private readonly tick = signal(0);
  readonly fresh: Signal<Freshness>;
  readonly everLoaded = signal(false);
  private readonly guard = requestGuard();

  constructor(
    private readonly fetch: () => Promise<T>,
    asOf: (d: T) => string | null | undefined,
    private readonly now: NowFn,
  ) {
    this.fresh = computed(() => {
      this.tick();
      const d = this.data();
      const f = freshness(d ? asOf(d) : null, this.now());
      // After a failed refresh the data is, by definition, not current.
      return this.unavailable() ? { ...f, stale: true } : f;
    });
  }

  /** Re-evaluate age without a fetch (call from a timer). */
  touch(): void {
    this.tick.update((n) => n + 1);
  }

  /** Forget everything and ignore any request still in flight (the question changed: another market, another range). */
  reset(): void {
    this.guard.invalidate();
    this.data.set(null);
    this.error.set(null);
    this.unavailable.set(false);
    this.loading.set(false);
  }

  /** Only the latest request may write state, including the loading flag. */
  async load(): Promise<void> {
    const current = this.guard.begin();
    this.loading.set(true);
    try {
      const data = await this.fetch();
      if (!current()) return;
      this.data.set(data);
      this.error.set(null);
      this.unavailable.set(false);
      this.everLoaded.set(true);
    } catch (e) {
      if (!current()) return;
      const p = ProblemError.from(e);
      // One failure shows at a time: a later result of the other kind replaces the earlier one.
      if (isUnavailableError(p)) {
        this.unavailable.set(true);
        this.error.set(null);
      } else {
        this.error.set(p);
        this.unavailable.set(false);
      }
    } finally {
      if (current()) this.loading.set(false);
    }
  }
}
