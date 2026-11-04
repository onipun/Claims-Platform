import { NgTemplateOutlet } from '@angular/common';
import { Component, inject, input, output, signal } from '@angular/core';
import { LucideAngularModule, Download, Loader2, Upload } from 'lucide-angular';
import { ProblemError } from '../../core/api/problem-error';
import { Announcer, UI } from '../../shared/ui';
import { ClaimantApi } from './claimant.api';
import { checkUpload, formatBytes, formatWhen } from './claimant.format';
import { humanise } from './claimant.labels';
import type { Checklist, ChecklistItem, DocumentView } from './claimant.types';

const OTHER = 'OTHER';

/** Message for a failed upload: the server's own words when it gave a problem document, else by status. */
export function uploadErrorText(e: ProblemError): string {
  if (e.status === 413 || e.code === 'PAYLOAD_TOO_LARGE') return 'The server rejected this file as too large. The limit is 10 MB.';
  if (e.status === 415 || e.code === 'UNSUPPORTED_MEDIA_TYPE') return 'The server does not accept this type of file. Upload a PDF, JPEG or PNG.';
  if (e.code === 'NETWORK') return 'Cannot reach the server. Check your connection and try again.';
  if (e.status >= 500) return 'The upload failed on our side. Try again shortly.';
  return e.detail ?? e.title;
}

@Component({
  selector: 'claimant-documents-panel',
  imports: [LucideAngularModule, NgTemplateOutlet, UI],
  template: `
    <section uiCard id="documents" aria-labelledby="docs-title" data-testid="documents">
      <div uiCardHeader>
        <h2 id="docs-title" uiCardTitle class="text-lg">Documents</h2>
        <p uiCardDescription data-testid="outstanding">
          @if ((checklist().outstanding ?? 0) > 0) {
            {{ checklist().outstanding }} {{ checklist().outstanding === 1 ? 'document is' : 'documents are' }} still outstanding. PDF, JPEG or PNG, up to 10 MB each.
          } @else {
            Nothing outstanding. PDF, JPEG or PNG, up to 10 MB each.
          }
        </p>
      </div>
      <div uiCardContent class="space-y-4">
        @if (downloadError()) { <p role="alert" class="text-sm text-red-600 dark:text-red-400">{{ downloadError() }}</p> }

        @for (item of checklist().items; track item.documentType) {
          <article class="rounded-md border p-4" [attr.data-testid]="'doc-' + item.documentType">
            <div class="flex flex-wrap items-start justify-between gap-3">
              <div class="min-w-0">
                <h3 class="font-medium">{{ name(item.documentType) }}</h3>
                @if (item.placeholder) { <p class="text-xs text-muted-foreground">Indicative requirement; your officer may ask for something different.</p> }
              </div>
              <span [class]="pill(item.status)" data-testid="doc-status">{{ statusText(item.status) }}</span>
            </div>
            @if (item.status === 'RE_REQUESTED' && item.reviewNote) {
              <p class="mt-2 rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 dark:bg-red-950/40 dark:text-red-200" data-testid="re-requested">
                <span class="font-medium">Please upload this again.</span> {{ item.reviewNote }}
              </p>
            }
            @if (item.uploads?.length) { <ng-container [ngTemplateOutlet]="uploads" [ngTemplateOutletContext]="{ $implicit: item.uploads }" /> }
            @if (item.status !== 'ACCEPTED') {
              <ng-container [ngTemplateOutlet]="uploader" [ngTemplateOutletContext]="{ $implicit: item.documentType, label: item.status === 'MISSING' ? 'Upload' : 'Upload again' }" />
            }
          </article>
        }

        <article class="rounded-md border p-4" data-testid="doc-OTHER">
          <h3 class="font-medium">Other documents</h3>
          <p class="text-xs text-muted-foreground">Anything else that supports your claim.</p>
          @if (checklist().other?.length) { <ng-container [ngTemplateOutlet]="uploads" [ngTemplateOutletContext]="{ $implicit: checklist().other }" /> }
          <ng-container [ngTemplateOutlet]="uploader" [ngTemplateOutletContext]="{ $implicit: other, label: 'Upload' }" />
        </article>
      </div>
    </section>

    <ng-template #uploads let-docs>
      <ul class="mt-3 divide-y rounded-md border text-sm">
        @for (d of docs; track d.id) {
          <li class="flex flex-wrap items-center justify-between gap-2 p-3">
            <span class="min-w-0">
              <span class="block break-all font-medium">{{ d.filename }}</span>
              <span class="block text-xs text-muted-foreground">{{ size(d.sizeBytes) }} &middot; uploaded {{ when(d.uploadedAt) }} &middot; {{ docStatus(d) }}</span>
              @if (d.reviewNote && d.status === 'RE_REQUESTED') { <span class="block text-xs text-red-700 dark:text-red-300">{{ d.reviewNote }}</span> }
            </span>
            <button uiButton variant="outline" size="sm" type="button" [attr.aria-disabled]="downloading() === d.id ? 'true' : null" (click)="download(d)">
              <lucide-icon [img]="icons.Download" class="mr-2 h-4 w-4" aria-hidden="true" />Download<span class="sr-only"> {{ d.filename }}</span>
            </button>
          </li>
        }
      </ul>
    </ng-template>

    <ng-template #uploader let-type let-label="label">
      <div class="mt-3">
        <input #f type="file" class="sr-only" tabindex="-1" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" [attr.aria-label]="'Choose a file for ' + name(type)" (change)="pick(type, f)" />
        <button uiButton variant="outline" size="sm" type="button" [attr.aria-disabled]="uploading() !== null ? 'true' : null" (click)="choose(f)"
          [attr.aria-label]="(uploading() === type ? 'Uploading ' : label + ' ') + name(type)" [attr.aria-describedby]="errors()[type] ? 'up-err-' + type : null">
          @if (uploading() === type) { <lucide-icon [img]="icons.Loader2" class="mr-2 h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />Uploading... }
          @else { <lucide-icon [img]="icons.Upload" class="mr-2 h-4 w-4" aria-hidden="true" />{{ label }} }
        </button>
        @if (errors()[type]) { <p [id]="'up-err-' + type" role="alert" class="mt-2 text-sm text-red-600 dark:text-red-400" data-testid="upload-error">{{ errors()[type] }}</p> }
      </div>
    </ng-template>
  `,
})
export class DocumentsPanel {
  private readonly api = inject(ClaimantApi);
  private readonly announcer = inject(Announcer);
  readonly claimId = input.required<string>();
  readonly checklist = input.required<Checklist>();
  readonly zone = input<string | null>();
  /** Fired after a successful upload so the page can reload the claim and checklist. */
  readonly uploaded = output<DocumentView>();

  protected readonly icons = { Download, Loader2, Upload };
  protected readonly other = OTHER;
  protected readonly uploading = signal<string | null>(null);
  protected readonly downloading = signal<string | null>(null);
  protected readonly errors = signal<Record<string, string>>({});
  protected readonly downloadError = signal<string | null>(null);

  protected name(t: string | undefined): string {
    return humanise(t);
  }
  protected size = formatBytes;
  protected when(v: string | undefined): string {
    return formatWhen(v, { zone: this.zone() });
  }
  protected statusText(s: string | undefined): string {
    return { MISSING: 'Needed', UPLOADED: 'Received, awaiting review', ACCEPTED: 'Accepted', RE_REQUESTED: 'Upload again' }[s ?? ''] ?? humanise(s);
  }
  protected docStatus(d: DocumentView): string {
    return this.statusText(d.status);
  }
  protected pill(s: ChecklistItem['status']): string {
    const tone =
      s === 'ACCEPTED' ? 'bg-green-100 text-green-800 dark:bg-green-950/50 dark:text-green-200'
      : s === 'UPLOADED' ? 'bg-blue-100 text-blue-800 dark:bg-blue-950/50 dark:text-blue-200'
      : s === 'RE_REQUESTED' ? 'bg-red-100 text-red-800 dark:bg-red-950/50 dark:text-red-200'
      : 'bg-orange-100 text-orange-800 dark:bg-orange-950/50 dark:text-orange-200';
    return `inline-flex items-center whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ${tone}`;
  }

  private setError(type: string, msg: string | null): void {
    this.errors.update((e) => {
      const next = { ...e };
      if (msg) next[type] = msg;
      else delete next[type];
      return next;
    });
  }

  /** aria-disabled keeps focus on the button during an upload; this guard refuses the click. */
  protected choose(f: HTMLInputElement): void {
    if (this.uploading() === null) f.click();
  }

  protected async pick(type: string, input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.uploading() !== null) return;
    const problem = checkUpload(file);
    if (problem) {
      this.setError(type, problem);
      return;
    }
    this.setError(type, null);
    this.uploading.set(type);
    try {
      const doc = await this.api.upload(this.claimId(), type, file);
      this.uploaded.emit(doc);
      this.announcer.polite(`${file.name} uploaded for ${this.name(type)}.`);
    } catch (e) {
      this.setError(type, uploadErrorText(ProblemError.from(e)));
    } finally {
      this.uploading.set(null);
    }
  }

  protected async download(d: DocumentView): Promise<void> {
    if (!d.id || this.downloading()) return;
    this.downloadError.set(null);
    this.downloading.set(d.id);
    try {
      const blob = await this.api.downloadDocument(this.claimId(), d.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = d.filename ?? 'document';
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (e) {
      const p = ProblemError.from(e);
      this.downloadError.set(p.status === 404 ? 'This document is no longer available.' : p.detail ?? p.title);
    } finally {
      this.downloading.set(null);
    }
  }
}
