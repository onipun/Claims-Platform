import { Component, ElementRef, Injector, afterNextRender, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideAngularModule, AlertCircle, FilePlus2, FileText } from 'lucide-angular';
import { loadable } from '../../core/util/loadable';
import { UI } from '../../shared/ui';
import { ClaimantApi } from './claimant.api';
import { claimTypeLabel } from './claimant.labels';
import { formatDay } from './claimant.format';
import type { ClaimPage } from './claimant.types';
import { Pager, focusPagerStatus } from './pager';

const PAGE_SIZE = 10;

@Component({
  selector: 'app-claims-list-page',
  imports: [RouterLink, LucideAngularModule, UI, Pager],
  template: `
    <div class="mx-auto max-w-5xl space-y-6">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="text-2xl font-bold tracking-tight">My claims</h1>
          <p class="text-sm text-muted-foreground">Every claim you have reported, newest first.</p>
        </div>
        <a uiButton routerLink="/claimant/new"><lucide-icon [img]="icons.FilePlus2" class="mr-2 h-4 w-4" aria-hidden="true" />Report a claim</a>
      </div>

      @switch (claims.state().status) {
        @case ('loading') {
          <section uiCard class="p-6" aria-busy="true"><ui-skeleton-table [rows]="5" [columns]="4" /></section>
        }
        @case ('error') {
          <section uiCard><ui-error-state [headingLevel]="2" [message]="claims.error()?.detail ?? claims.error()?.title ?? ''" [errorId]="claims.error()?.errorId" (retry)="claims.load()" /></section>
        }
        @case ('empty') {
          <ui-empty-state [headingLevel]="2" title="You have not reported a claim yet" description="When something happens to your vehicle or property, report it here and we will take it from there.">
            <a uiButton routerLink="/claimant/new">Report a claim</a>
          </ui-empty-state>
        }
        @case ('ready') {
          @if (claims.data(); as p) {
            <section uiCard>
              <div uiTableContainer>
                <table uiTable data-testid="claims-table">
                  <caption class="sr-only">Your claims</caption>
                  <thead uiThead>
                    <tr uiTr>
                      <th uiTh scope="col">Claim</th>
                      <th uiTh scope="col" class="hidden md:table-cell">Type</th>
                      <th uiTh scope="col">Status</th>
                      <th uiTh scope="col" class="hidden sm:table-cell">Incident</th>
                      <th uiTh scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody uiTbody>
                    @for (c of p.items; track c.id) {
                      <tr uiTr>
                        <td uiTd>
                          <a [routerLink]="['/claimant/claims', c.id]" class="font-medium text-primary underline-offset-4 hover:underline">{{ c.claimNumber }}</a>
                          <div class="mt-0.5 text-xs text-muted-foreground md:hidden">{{ type(c.claimType) }}</div>
                        </td>
                        <td uiTd class="hidden md:table-cell">{{ type(c.claimType) }}</td>
                        <td uiTd><claim-status-badge [status]="c.status" /></td>
                        <td uiTd class="hidden whitespace-nowrap sm:table-cell">{{ day(c.incidentAtLocal) }}</td>
                        <td uiTd>
                          @if (c.actionRequired) {
                            <span class="inline-flex items-center gap-1 whitespace-nowrap rounded-full bg-orange-100 px-2.5 py-0.5 text-xs font-medium text-orange-800 dark:bg-orange-950/50 dark:text-orange-200">
                              <lucide-icon [img]="icons.AlertCircle" class="h-3.5 w-3.5" aria-hidden="true" />Action needed
                            </span>
                          } @else {
                            <span class="text-muted-foreground"><span aria-hidden="true">&mdash;</span><span class="sr-only">No action needed</span></span>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
            <claimant-pager [page]="p.page ?? 0" [totalPages]="p.totalPages ?? 0" [total]="p.totalElements ?? 0" (go)="goto($event)" />
          }
        }
      }
    </div>
  `,
})
export class ClaimsListPage {
  private readonly api = inject(ClaimantApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  protected readonly icons = { AlertCircle, FilePlus2, FileText };
  private readonly page = signal(0);
  protected readonly claims = loadable<ClaimPage>(() => this.api.listClaims(this.page(), PAGE_SIZE), { isEmpty: (p) => !p.items?.length });
  protected readonly type = claimTypeLabel;
  protected readonly day = (v: string | undefined) => formatDay(v);

  constructor() {
    void this.claims.load();
  }

  protected async goto(p: number): Promise<void> {
    this.page.set(p);
    await this.claims.load();
    afterNextRender(() => focusPagerStatus(this.host), { injector: this.injector });
  }
}
