import { NgTemplateOutlet } from '@angular/common';
import { Component, Injectable, computed, signal } from '@angular/core';
import { LucideAngularModule, AlertCircle, CheckCircle2, Info, X } from 'lucide-angular';

export type ToastKind = 'success' | 'error' | 'info';
export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

/** Success and info toasts go away after this long, unless the pointer or keyboard focus is on them. */
export const TOAST_MS = 5000;

interface Timer {
  handle: ReturnType<typeof setTimeout> | null;
  remaining: number;
  startedAt: number;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  private seq = 0;
  readonly toasts = signal<readonly Toast[]>([]);
  private readonly timers = new Map<number, Timer>();

  /**
   * Errors never auto-dismiss (WCAG 2.2.1: the reader may need longer than any timer, and an error has to
   * be acted on). Others dismiss after `durationMs`; the countdown pauses while hovered or focused.
   */
  show(message: string, kind: ToastKind = 'info', durationMs = kind === 'error' ? 0 : TOAST_MS): number {
    const id = ++this.seq;
    this.toasts.update((t) => [...t, { id, kind, message }]);
    if (durationMs > 0) {
      this.timers.set(id, { handle: null, remaining: durationMs, startedAt: 0 });
      this.resume(id);
    }
    return id;
  }
  success(message: string): number {
    return this.show(message, 'success');
  }
  error(message: string): number {
    return this.show(message, 'error');
  }
  pause(id: number): void {
    const t = this.timers.get(id);
    if (!t || t.handle === null) return;
    clearTimeout(t.handle);
    t.handle = null;
    t.remaining = Math.max(1000, t.remaining - (Date.now() - t.startedAt));
  }
  resume(id: number): void {
    const t = this.timers.get(id);
    if (!t || t.handle !== null) return;
    t.startedAt = Date.now();
    t.handle = setTimeout(() => this.dismiss(id), t.remaining);
  }
  dismiss(id: number): void {
    const t = this.timers.get(id);
    if (t?.handle) clearTimeout(t.handle);
    this.timers.delete(id);
    this.toasts.update((x) => x.filter((y) => y.id !== id));
  }
}

/**
 * Mount once in the app root. The two live regions are permanent: a region created together with its
 * content is often not announced, so the container exists first and toasts are added to it. Errors go
 * to the assertive region, the rest to the polite one.
 */
@Component({
  selector: 'ui-toast-host',
  imports: [LucideAngularModule, NgTemplateOutlet],
  host: { class: 'pointer-events-none fixed bottom-4 right-4 z-[60] flex w-full max-w-sm flex-col gap-2 px-4 sm:px-0' },
  template: `
    <div role="status" aria-live="polite" aria-atomic="false" class="flex flex-col gap-2" data-testid="toasts-polite">
      @for (t of polite(); track t.id) {
        <ng-container [ngTemplateOutlet]="toast" [ngTemplateOutletContext]="{ $implicit: t }" />
      }
    </div>
    <div role="alert" aria-live="assertive" aria-atomic="false" class="flex flex-col gap-2" data-testid="toasts-alert">
      @for (t of errors(); track t.id) {
        <ng-container [ngTemplateOutlet]="toast" [ngTemplateOutletContext]="{ $implicit: t }" />
      }
    </div>

    <ng-template #toast let-t>
      <div
        class="pointer-events-auto flex items-start gap-3 rounded-lg border bg-popover p-4 text-sm text-popover-foreground shadow-md"
        [attr.data-kind]="t.kind"
        (mouseenter)="service.pause(t.id)"
        (mouseleave)="service.resume(t.id)"
        (focusin)="service.pause(t.id)"
        (focusout)="service.resume(t.id)"
      >
        <lucide-icon
          [img]="icon(t.kind)"
          class="mt-0.5 h-4 w-4 flex-shrink-0"
          [class.text-green-700]="t.kind === 'success'"
          [class.text-red-600]="t.kind === 'error'"
          [class.text-primary]="t.kind === 'info'"
          aria-hidden="true"
        />
        <p class="flex-1">{{ t.message }}</p>
        <button
          type="button"
          class="-m-1 inline-flex h-8 w-8 items-center justify-center rounded-sm opacity-70 hover:opacity-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          (click)="service.dismiss(t.id)"
        >
          <lucide-icon [img]="closeIcon" class="h-4 w-4" aria-hidden="true" />
          <span class="sr-only">Dismiss</span>
        </button>
      </div>
    </ng-template>
  `,
})
export class UiToastHost {
  constructor(protected readonly service: ToastService) {}
  protected readonly closeIcon = X;
  protected readonly polite = computed(() => this.service.toasts().filter((t) => t.kind !== 'error'));
  protected readonly errors = computed(() => this.service.toasts().filter((t) => t.kind === 'error'));
  protected icon = (k: ToastKind) => (k === 'success' ? CheckCircle2 : k === 'error' ? AlertCircle : Info);
}
