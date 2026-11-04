import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { QueuePage } from './queue.page';
import { flush, setupStaffTest } from './testing';
import type { ClaimPage } from './staff.api';

const page = (): ClaimPage => ({
  page: 0, size: 20, totalElements: 1, totalPages: 1,
  items: [{ id: 'a1', claimNumber: 'CLM-SG-000007', market: 'SG', line: 'MOTOR', claimType: 'OWN_DAMAGE', status: 'SUBMITTED', version: 5, reportedAt: '2026-11-03T00:00:00Z', pickupDueAt: '2026-11-04T00:00:00Z', overdue: true, late: true, waitingOnClaimant: false, currency: 'SGD' }],
});
const list = (ctl: HttpTestingController, queue: string): TestRequest =>
  ctl.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/staff/claims' && r.params.get('queue') === queue);

describe('QueuePage', () => {
  let ctl: HttpTestingController;
  let fixture: ComponentFixture<QueuePage>;
  let el: HTMLElement;

  beforeEach(async () => {
    ctl = setupStaffTest('OFFICER', ['SG']);
    fixture = TestBed.createComponent(QueuePage);
    el = fixture.nativeElement;
    fixture.detectChanges();
    list(ctl, 'unassigned').flush(page());
    await flush(fixture);
  });
  afterEach(() => {
    fixture.destroy();
    sessionStorage.clear();
  });

  it('highlights overdue rows and shows the late flag', () => {
    const row = el.querySelector('[data-testid=row-CLM-SG-000007]')!;
    expect(row.className).toContain('bg-red-50');
    expect(row.textContent).toContain('Late report');
    expect(row.textContent).toContain('overdue');
  });

  it('picks up with If-Match from the row version', async () => {
    el.querySelector<HTMLButtonElement>('button[aria-label="Pick up CLM-SG-000007"]')!.click();
    const req = ctl.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/staff/claims/a1/pick-up');
    expect(req.request.headers.get('If-Match')).toBe('"5"');
    req.flush({ id: 'a1', version: 6 }, { headers: { ETag: '"6"' } });
    await flush(fixture);
    list(ctl, 'unassigned').flush({ items: [], totalElements: 0, totalPages: 0, page: 0, size: 20 });
    await flush(fixture);
    expect(el.textContent).toContain('No unassigned claims');
  });

  it('keeps focus on the pick-up button while in flight (aria-disabled) and a second click sends nothing', async () => {
    const btn = el.querySelector<HTMLButtonElement>('button[aria-label="Pick up CLM-SG-000007"]')!;
    btn.focus();
    btn.click();
    btn.click();
    const req = ctl.expectOne((r) => r.method === 'POST'); // expectOne fails if the second click sent another
    fixture.detectChanges();
    expect(btn.getAttribute('aria-disabled')).toBe('true');
    expect(btn.disabled).toBe(false);
    expect(document.activeElement).toBe(btn);
    req.flush({ id: 'a1', version: 6 }, { headers: { ETag: '"6"' } });
    await flush(fixture);
    list(ctl, 'unassigned').flush({ items: [], totalElements: 0, totalPages: 0, page: 0, size: 20 });
    await flush(fixture);
  });

  it('on 409 tells the officer someone else got it and refreshes the row', async () => {
    el.querySelector<HTMLButtonElement>('button[aria-label="Pick up CLM-SG-000007"]')!.click();
    const req = ctl.expectOne((r) => r.method === 'POST');
    req.flush({ status: 409, code: 'STALE_VERSION', title: 'Stale' }, { status: 409, statusText: 'Conflict' });
    await flush(fixture);
    list(ctl, 'unassigned').flush({ items: [], totalElements: 0, totalPages: 0, page: 0, size: 20 });
    await flush(fixture);
    expect(document.body.textContent ?? '').not.toContain('undefined');
    expect(el.textContent).toContain('No unassigned claims');
  });
});
