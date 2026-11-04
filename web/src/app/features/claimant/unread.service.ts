import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { requestGuard } from '../../core/util/latest';
import { PollHandle, poll } from '../../core/util/poll';
import type { NavBadge } from '../../layout/nav';
import { ClaimantApi } from './claimant.api';
import { instantMs } from './claimant.format';

export const LAST_SEEN_KEY = 'cp.claimant.updates.lastSeen';
/** Updates are counted over the newest WINDOW; at the cap the badge says "50+". */
export const WINDOW = 50;

/** One key per user, so a second claimant in the same browser never inherits the first one's "seen" time. */
export const lastSeenKey = (userId: string): string => `${LAST_SEEN_KEY}.${userId}`;

interface State {
  /** Whose numbers these are. When the signed-in user differs, the state is not theirs and reads as empty. */
  userId: string | null;
  seen: number;
  latest: readonly number[];
  failed: boolean;
}

const EMPTY: State = { userId: null, seen: 0, latest: [], failed: false };

/**
 * Unread count for the Updates nav badge. STATED SHORTCUT: "seen" is a per-browser, per-user timestamp in
 * localStorage, not a server-side read receipt, so it is not shared across devices. It is a preference,
 * not sensitive data; every access is wrapped because storage can be blocked or full.
 */
@Injectable({ providedIn: 'root' })
export class UnreadUpdates {
  private readonly api = inject(ClaimantApi);
  private readonly auth = inject(AuthService);
  private readonly guard = requestGuard();
  private readonly state = signal<State>(EMPTY);

  private userId(): string | null {
    const s = this.auth.session();
    return s && s.role === 'CLAIMANT' && s.userId ? s.userId : null;
  }
  /** The state if it belongs to the signed-in user, else an empty one. */
  private mine = computed(() => (this.state().userId === this.userId() ? this.state() : EMPTY));

  /** Updates newer than the last visit, counted over the newest 50. */
  readonly count = computed(() => {
    const m = this.mine();
    return m.latest.filter((t) => t > m.seen).length;
  });
  /** What the nav shows: nothing, a number, "50+" at the cap, or a neutral "?" when the count could not be loaded. */
  readonly badge: Signal<NavBadge> = computed(() => {
    const m = this.mine();
    const n = this.count();
    if (n >= WINDOW) return { text: `${WINDOW}+`, label: `${WINDOW} or more unread` };
    if (n > 0) return { text: String(n), label: 'unread' };
    if (m.failed) return { text: '?', label: 'unread count unavailable' };
    return null;
  });

  /** Switches to the signed-in user's state (reading their stored "seen" time). Returns their id, or null. */
  private bind(): string | null {
    const uid = this.userId();
    if (this.state().userId !== uid) {
      this.guard.invalidate();
      this.state.set(uid ? { userId: uid, seen: read(uid), latest: [], failed: false } : EMPTY);
    }
    return uid;
  }

  /** The last-seen instant (epoch ms) as it was before this visit, for highlighting new items. */
  seenAt(): number {
    this.bind();
    return this.mine().seen;
  }

  async refresh(): Promise<void> {
    // The route injector outlives a sign-out; never call the API without a claimant session.
    const uid = this.bind();
    if (!uid) return;
    const current = this.guard.begin();
    try {
      const page = await this.api.updates(0, WINDOW);
      // The user may have changed while the request was out.
      if (!current() || this.userId() !== uid) return;
      const latest = (page.items ?? []).map((u) => instantMs(u.at)).filter((t) => !Number.isNaN(t));
      this.state.update((s) => ({ ...s, latest, failed: false }));
    } catch {
      if (!current() || this.userId() !== uid) return;
      this.state.update((s) => ({ ...s, failed: true }));
    }
  }

  /** Mark everything up to `newest` (the newest update the user was actually shown) as seen. Never moves backwards. */
  markSeen(newest: number): void {
    const uid = this.bind();
    if (!uid || Number.isNaN(newest)) return;
    const seen = Math.max(this.mine().seen, newest);
    this.state.update((s) => ({ ...s, seen }));
    try {
      localStorage.setItem(lastSeenKey(uid), String(seen));
    } catch {
      // Storage unavailable: the badge just resets on reload.
    }
  }

  /** Start polling. Needs an injection context; the shell calls it, so the poll ends when the shell is destroyed. */
  start(everyMs = 60_000): PollHandle {
    void this.refresh();
    return poll(() => this.refresh(), everyMs);
  }
}

function read(uid: string): number {
  try {
    const v = Number(localStorage.getItem(lastSeenKey(uid)));
    return Number.isFinite(v) ? v : 0;
  } catch {
    return 0;
  }
}

/** Route-level provider factory for the shell's nav badges. It only exposes signals; the shell starts the poll. */
export function claimantNavBadges(): Record<string, Signal<NavBadge>> {
  return { updates: inject(UnreadUpdates).badge };
}

/** Route-level provider factory: what the shell runs when it is created. */
export function claimantNavActivate(): () => void {
  const unread = inject(UnreadUpdates);
  return () => void unread.start();
}
