import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { Announcer } from '../shared/ui/announcer';

const pathOf = (url: string): string => url.split(/[?#]/)[0];

/**
 * After a real navigation, move focus to the page heading (or `main`) and announce it, so keyboard and
 * screen-reader users are not left on a link that no longer exists (WCAG 2.4.3, 4.1.3). Skipped for the
 * first load (the browser already starts at the top) and for query- or fragment-only changes such as a
 * tab or filter change, which stay on the same page.
 */
@Injectable({ providedIn: 'root' })
export class RouteFocus {
  private readonly doc = inject(DOCUMENT);
  private readonly announcer = inject(Announcer);
  private previous: string | null = null;
  /** How long to wait for a heading: `retries` x `retryMs` (about 2 s). Public so a test can shorten it. */
  retryMs = 100;
  retries = 20;

  constructor() {
    inject(Router).events.subscribe((e) => {
      if (!(e instanceof NavigationEnd)) return;
      const path = pathOf(e.urlAfterRedirects);
      const first = this.previous === null;
      const same = this.previous === path;
      this.previous = path;
      if (first || same) return;
      // The link the user just followed is usually still in the DOM and still focused. Remember it, so that later
      // it can be told apart from a control the user moved to on purpose.
      this.followed = this.doc.activeElement;
      // Let the routed component render its heading first.
      this.cancel();
      this.timer = setTimeout(() => this.focusPage(), 0);
    });
  }

  private followed: Element | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;

  private cancel(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  /**
   * Focus the page's h1 once it exists. Data pages render it only after their first read, so while it is missing
   * this tries again every `RETRY_MS`, at most `RETRIES` times; then `main` takes the focus.
   */
  focusPage(attempt = 0): void {
    this.cancel();
    const main = this.doc.querySelector<HTMLElement>('main');
    if (!main) return;
    const h1 = main.querySelector<HTMLElement>('h1');
    if (!h1 && attempt < this.retries) {
      this.timer = setTimeout(() => this.focusPage(attempt + 1), this.retryMs);
      return;
    }
    // While waiting, the user may have started on the page (a field, a menu). Never pull focus out of that.
    // Focus on <body>, on `main`, on an element that left the DOM, or still on the element that was focused when the
    // navigation ended (the link just followed) is up for grabs.
    const active = this.doc.activeElement as HTMLElement | null;
    const free = !active || active === this.doc.body || active === main || !this.doc.contains(active) || active === this.followed;
    if (!free && attempt > 0) {
      // The user is elsewhere now. Leave focus alone, but still tell them where the navigation went.
      const name = this.doc.title;
      if (name) this.announcer.polite(`Navigated to ${name}`);
      return;
    }
    const target = h1 ?? main;
    if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
    target.focus({ preventScroll: false });
    // Focus on the h1 already makes the screen reader say it. Only `main` needs the announcement.
    if (!h1) {
      const name = this.doc.title;
      if (name) this.announcer.polite(`Navigated to ${name}`);
    }
  }
}
