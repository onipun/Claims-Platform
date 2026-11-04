import { NgTemplateOutlet } from '@angular/common';
import { Component, ElementRef, Injector, afterNextRender, inject, input, output, signal } from '@angular/core';
import { LucideAngularModule, Download, Loader2 } from 'lucide-angular';
import { ProblemError } from '../../core/api/problem-error';
import { ToastService, UI } from '../../shared/ui';
import { DocumentChecklist, DocumentView, StaffApi } from './staff.api';
import { formatBytes, formatWhen, label } from './format';

/**
 * Document checklist with authenticated download (Blob fetch through the HttpClient, so the bearer is
 * sent) and accept / re-request. `canReview` comes from the workspace; the server decides for real.
 */
@Component({
  selector: 'app-claim-documents',
  imports: [LucideAngularModule, NgTemplateOutlet, UI],
  template: `
    <section uiCard aria-labelledby="docs-h">
      <div uiCardHeader>
        <h2 id="docs-h" uiCardTitle class="text-lg focus:outline-none" tabindex="-1">Documents</h2>
        <p uiCardDescription>
          @if (checklist(); as c) { {{ c.outstanding ?? 0 }} outstanding on the checklist. } @else { Checklist and uploads. }
        </p>
      </div>
      <div uiCardContent class="space-y-4">
        @if (loading()) {
          <ui-skeleton-table [rows]="3" [columns]="3" />
        } @else if (loadError(); as e) {
          <ui-error-state title="Could not load documents" [message]="e.detail ?? e.title" [errorId]="e.errorId" (retry)="retry.emit()" />
        } @else if (checklist(); as c) {
          @if (!(c.items?.length) && !(c.other?.length)) {
            <ui-empty-state title="No documents yet" description="Nothing has been uploaded or requested for this claim." />
          }
          @for (item of c.items ?? []; track item.documentType) {
            <div class="rounded-md border">
              <div class="flex flex-wrap items-center justify-between gap-2 border-b bg-muted/40 px-3 py-2 text-sm">
                <span class="font-medium">{{ labelOf(item.documentType) }}</span>
                <span uiBadge [variant]="item.placeholder && !(item.uploads?.length) ? 'outline' : 'secondary'">{{ item.placeholder && !(item.uploads?.length) ? 'Not uploaded' : labelOf(item.status) }}</span>
              </div>
              @if (item.reviewNote) { <p class="px-3 pt-2 text-xs text-muted-foreground">Review note: {{ item.reviewNote }}</p> }
              @for (u of item.uploads ?? []; track u.id) {
                <ng-container *ngTemplateOutlet="row; context: { $implicit: u }" />
              }
            </div>
          }
          @if (c.other?.length) {
            <div class="rounded-md border">
              <div class="border-b bg-muted/40 px-3 py-2 text-sm font-medium">Other uploads</div>
              @for (u of c.other; track u.id) {
                <ng-container *ngTemplateOutlet="row; context: { $implicit: u }" />
              }
            </div>
          }
        }
      </div>
    </section>

    <ng-template #row let-u>
      <div class="border-b px-3 py-2 text-sm last:border-b-0" [attr.data-testid]="'doc-' + u.id">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="min-w-0">
            <p class="truncate font-medium">{{ u.filename }}</p>
            <p class="text-xs text-muted-foreground tabular-nums">{{ labelOf(u.documentType) }} · {{ size(u.sizeBytes) }} · uploaded {{ when(u.uploadedAt) }}</p>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <span uiBadge [variant]="u.status === 'RE_REQUESTED' ? 'destructive' : 'secondary'">{{ labelOf(u.status) }}</span>
            <button uiButton variant="outline" size="sm" type="button" [attr.aria-disabled]="downloading() === u.id ? 'true' : null" (click)="download(u)" [attr.aria-label]="'Download ' + u.filename">
              <lucide-icon [img]="downloading() === u.id ? icons.Loader2 : icons.Download" class="mr-1 h-4 w-4" [class.animate-spin]="downloading() === u.id" aria-hidden="true" /> Download
            </button>
            @if (canReview() && u.status === 'UPLOADED') {
              <button uiButton size="sm" type="button" [attr.aria-disabled]="reviewing() !== null ? 'true' : null" (click)="review(u, 'ACCEPTED')">Accept</button>
              <button uiButton variant="outline" size="sm" type="button" [attr.aria-expanded]="rerequest() === u.id" (click)="rerequest.set(rerequest() === u.id ? null : u.id)">Re-request…</button>
            }
          </div>
        </div>
        @if (u.reviewNote) { <p class="mt-1 text-xs text-muted-foreground">Review note: {{ u.reviewNote }}</p> }
        @if (rerequest() === u.id) {
          <div class="mt-2 space-y-2">
            <ui-required-legend />
            <ui-form-field label="Reason for re-request" [required]="true" [error]="reasonError()" hint="The claimant will see this reason.">
              <textarea uiTextarea rows="2" [value]="reason()" (input)="reason.set($any($event.target).value)"></textarea>
            </ui-form-field>
            <button uiButton size="sm" type="button" [attr.aria-disabled]="reviewing() !== null ? 'true' : null" (click)="review(u, 'RE_REQUESTED')">Send re-request</button>
          </div>
        }
      </div>
    </ng-template>
  `,
})
export class ClaimDocuments {
  readonly claimId = input.required<string>();
  readonly checklist = input<DocumentChecklist | null>(null);
  readonly loading = input(false);
  readonly loadError = input<ProblemError | null>(null);
  readonly canReview = input(false);
  readonly retry = output();
  readonly reviewed = output();

  private readonly api = inject(StaffApi);
  private readonly toast = inject(ToastService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly icons = { Download, Loader2 };
  protected readonly labelOf = label;
  protected readonly size = formatBytes;
  protected readonly when = formatWhen;
  protected readonly downloading = signal<string | null>(null);
  protected readonly reviewing = signal<string | null>(null);
  protected readonly rerequest = signal<string | null>(null);
  protected readonly reason = signal('');
  protected readonly reasonError = signal<string | null>(null);

  protected async download(u: DocumentView): Promise<void> {
    // aria-disabled keeps focus on the button while the download runs; this guard refuses a second click.
    if (!u.id || this.downloading() === u.id) return;
    this.downloading.set(u.id);
    try {
      const blob = await this.api.downloadDocument(this.claimId(), u.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = u.filename ?? 'document';
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (e) {
      const p = ProblemError.from(e);
      this.toast.error(p.status === 404 ? 'That file is no longer available.' : (p.detail ?? p.title));
    } finally {
      this.downloading.set(null);
    }
  }

  protected async review(u: DocumentView, decision: 'ACCEPTED' | 'RE_REQUESTED'): Promise<void> {
    if (!u.id || this.reviewing() !== null) return;
    const reason = this.reason().trim();
    if (decision === 'RE_REQUESTED' && !reason) {
      this.reasonError.set('Say what the claimant should fix.');
      return;
    }
    this.reasonError.set(null);
    this.reviewing.set(u.id);
    try {
      await this.api.reviewDocument(this.claimId(), u.id, decision === 'ACCEPTED' ? { decision } : { decision, reason });
      this.toast.success(decision === 'ACCEPTED' ? 'Document accepted.' : 'Re-request sent to the claimant.');
      this.rerequest.set(null);
      this.reason.set('');
      this.reviewed.emit();
      // The reviewed row's buttons are replaced when the checklist reloads: park focus on this card's heading.
      afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('#docs-h')?.focus(), { injector: this.injector });
    } catch (e) {
      const p = ProblemError.from(e);
      this.reasonError.set(decision === 'RE_REQUESTED' ? (p.fieldMessage('reason') ?? null) : null);
      this.toast.error(p.detail ?? p.title);
    } finally {
      this.reviewing.set(null);
    }
  }
}
