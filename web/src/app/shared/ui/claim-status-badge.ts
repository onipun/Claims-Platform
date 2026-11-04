import { Component, computed, input } from '@angular/core';

// Pattern from FleetROS merchant-status-badge.tsx: rounded-full pill, bg-*-100 text-*-800.
const STYLES: Record<string, string> = {
  SUBMITTED: 'bg-blue-100 text-blue-800',
  UNDER_REVIEW: 'bg-indigo-100 text-indigo-800',
  AWAITING_INFO: 'bg-orange-100 text-orange-800',
  ASSESSED: 'bg-yellow-100 text-yellow-800',
  APPROVED: 'bg-green-100 text-green-800',
  SETTLED: 'bg-emerald-100 text-emerald-800',
  REJECTED: 'bg-red-100 text-red-800',
};
const FALLBACK = 'bg-gray-100 text-gray-800';

@Component({
  selector: 'claim-status-badge',
  host: { class: 'inline-flex' },
  template: `<span [class]="classes()">{{ text() }}</span>`,
})
export class ClaimStatusBadge {
  readonly status = input<string | null | undefined>();
  private readonly key = computed(() => (this.status() || 'SUBMITTED').toUpperCase().replace(/ /g, '_'));
  protected text = computed(() => this.key().replace(/_/g, ' '));
  protected classes = computed(
    () => `inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${STYLES[this.key()] ?? FALLBACK}`,
  );
}
