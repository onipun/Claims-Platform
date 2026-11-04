import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';

/** Light/dark. Follows the OS until toggled; the choice lasts for the page session (nothing is persisted). */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  readonly dark = signal(this.doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false);

  constructor() {
    effect(() => this.doc.documentElement.classList.toggle('dark', this.dark()));
  }
  toggle(): void {
    this.dark.update((d) => !d);
  }
}
