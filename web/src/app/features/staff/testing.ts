import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SESSION_KEY } from '../../core/auth/auth.service';
import type { Role } from '../../core/auth/roles';
import { makeToken } from '../../../testing/token';
import type { StaffClaimDetail } from './staff.api';

/** Test helpers shared by the staff specs. */
export function setupStaffTest(role: Role = 'OFFICER', markets = ['SG', 'MY'], extra: Parameters<typeof TestBed.configureTestingModule>[0] = {}) {
  sessionStorage.setItem(SESSION_KEY, makeToken({ role, markets, name: 'Olivia Officer' }));
  TestBed.configureTestingModule({
    ...extra,
    providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), ...(extra.providers ?? [])],
  });
  return TestBed.inject(HttpTestingController);
}

export const CLAIM_ID = '11111111-1111-1111-1111-111111111111';

export function detail(over: Partial<StaffClaimDetail> = {}): StaffClaimDetail {
  return {
    id: CLAIM_ID, claimNumber: 'CLM-SG-000042', status: 'UNDER_REVIEW', line: 'MOTOR', claimType: 'OWN_DAMAGE', market: 'SG',
    marketZone: 'Asia/Singapore', currency: 'SGD', policyNumber: 'POL-SG-1001', estimate: '4200.00',
    claimant: { id: 'c1', name: 'Chen Wei' }, assignedOfficer: { id: 'o1', name: 'Olivia Officer' },
    incidentAtLocal: '2026-10-30T22:15:00', reportedAt: '2026-11-02T01:00:00Z', late: true, lateReason: 'Was abroad',
    version: 3, allowedActions: ['ADD_NOTE', 'REQUEST_INFO', 'ASSESS', 'REJECT'], yourAuthorityLimit: '5000.00',
    ledger: { coverageLimit: '50000.00', deductible: '500.00', reserve: '3700.00', reserveStatus: 'PROVISIONAL', paid: '0.00', outstanding: '3700.00', incurred: '3700.00' },
    deadlines: { overdue: false, waitingOnClaimant: false, decisionDueAt: '2026-11-16T01:00:00Z' },
    timeline: [
      { at: '2026-11-02T01:00:00Z', actorName: 'Chen Wei', type: 'SUBMITTED', visibility: 'CLAIMANT', message: 'Claim submitted' },
      { at: '2026-11-03T02:00:00Z', actorName: 'Olivia Officer', type: 'NOTE', visibility: 'INTERNAL', message: 'Photos look consistent' },
    ],
    ...over,
  };
}

export const flush = async (fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) => {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((r) => setTimeout(r));
  fixture.detectChanges();
};

/** formatMoney separates currency and amount with a no-break space; compare with a plain one. */
export const norm = (s: string | null | undefined): string => (s ?? '').replace(/\u00a0/g, ' ');
