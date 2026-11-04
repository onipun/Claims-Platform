import { LiveAnnouncer } from '@angular/cdk/a11y';
import { Injectable, inject } from '@angular/core';

/**
 * The one screen-reader announcer (CDK LiveAnnouncer keeps a single permanent live region).
 * Call it on transitions only: an upload finished, a claim was confirmed, a polled claim changed,
 * a view became stale. Never from a timer tick, an "as of" label or anything that repeats unchanged.
 */
@Injectable({ providedIn: 'root' })
export class Announcer {
  private readonly live = inject(LiveAnnouncer);

  polite(message: string): void {
    void this.live.announce(message, 'polite');
  }
  assertive(message: string): void {
    void this.live.announce(message, 'assertive');
  }
}
