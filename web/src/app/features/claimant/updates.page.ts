import { Component, ElementRef, Injector, afterNextRender, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { loadable } from '../../core/util/loadable';
import { UI } from '../../shared/ui';
import { ClaimantApi } from './claimant.api';
import { formatWhen, instantMs } from './claimant.format';
import type { UpdatePage } from './claimant.types';
import { Pager, focusPagerStatus } from './pager';
import { UnreadUpdates } from './unread.service';

const PAGE_SIZE = 15;

@Component({
  selector: 'app-updates-page',
  imports: [RouterLink, UI, Pager],
  template: `
    <div class="mx-auto max-w-3xl space-y-6">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">Updates</h1>
        <p class="text-sm text-muted-foreground">Status changes and messages across all of your claims.</p>
      </div>

      @switch (feed.state().status) {
        @case ('loading') {
          <section uiCard class="space-y-4 p-6" aria-busy="true"><ui-skeleton-text [lines]="2" /><ui-skeleton-text [lines]="2" /><ui-skeleton-text [lines]="2" /></section>
        }
        @case ('error') {
          <section uiCard><ui-error-state [headingLevel]="2" [message]="feed.error()?.detail ?? feed.error()?.title ?? ''" [errorId]="feed.error()?.errorId" (retry)="feed.load()" /></section>
        }
        @case ('empty') {
          <ui-empty-state [headingLevel]="2" title="No updates yet" description="When your claim moves forward or we ask you something, it shows up here." />
        }
        @case ('ready') {
          @if (feed.data(); as p) {
            <ol uiCard class="divide-y" data-testid="updates-feed">
              @for (u of p.items; track u.id) {
                <li class="flex gap-3 p-4" [attr.data-unread]="isNew(u.at)">
                  <span class="mt-1.5 h-2.5 w-2.5 flex-shrink-0 rounded-full" [class.bg-primary]="isNew(u.at)" [class.bg-transparent]="!isNew(u.at)" aria-hidden="true"></span>
                  <div class="min-w-0 flex-1 space-y-1">
                    <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
                      <a [routerLink]="['/claimant/claims', u.claimId]" class="font-medium text-primary underline-offset-4 hover:underline">{{ u.claimNumber }}</a>
                      @if (u.toStatus) { <claim-status-badge [status]="u.toStatus" /> }
                      @if (isNew(u.at)) { <span class="text-xs font-semibold text-primary">New</span> }
                    </div>
                    <p class="break-words text-sm">{{ u.message }}</p>
                    <p class="text-xs text-muted-foreground">{{ when(u.at) }}@if (u.actorName) { &middot; {{ u.actorName }} }</p>
                  </div>
                </li>
              }
            </ol>
            <claimant-pager [page]="p.page ?? 0" [totalPages]="p.totalPages ?? 0" [total]="p.totalElements ?? 0" (go)="goto($event)" />
          }
        }
      }
    </div>
  `,
})
export class UpdatesPage {
  private readonly api = inject(ClaimantApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  private readonly unread = inject(UnreadUpdates);
  private readonly page = signal(0);
  /** Last-seen instant as of when this page was opened; items after it are highlighted as new. */
  private readonly seenBefore = this.unread.seenAt();
  protected readonly feed = loadable<UpdatePage>(() => this.api.updates(this.page(), PAGE_SIZE), { isEmpty: (p) => !p.items?.length });
  protected readonly when = (v: string | undefined) => formatWhen(v);

  constructor() {
    void this.feed.load().then(() => {
      // Mark seen only up to the newest update the user was actually shown, never "now".
      const shown = (this.feed.data()?.items ?? []).map((u) => instantMs(u.at)).filter((t) => !Number.isNaN(t));
      if (shown.length > 0) this.unread.markSeen(Math.max(...shown));
    });
  }

  protected isNew(at: string | undefined): boolean {
    return instantMs(at) > this.seenBefore;
  }

  protected async goto(p: number): Promise<void> {
    this.page.set(p);
    await this.feed.load();
    afterNextRender(() => focusPagerStatus(this.host), { injector: this.injector });
  }
}
