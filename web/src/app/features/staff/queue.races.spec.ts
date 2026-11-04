import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ToastService } from '../../shared/ui';
import { QueuePage } from './queue.page';
import { flush, setupStaffTest } from './testing';
import type { ClaimPage, StaffClaimSummary } from './staff.api';

const row = (n: number, over: Partial<StaffClaimSummary> = {}): StaffClaimSummary => ({
  id: `a${n}`, claimNumber: `CLM-SG-00000${n}`, market: 'SG', line: 'MOTOR', claimType: 'OWN_DAMAGE', status: 'SUBMITTED', version: 5,
  reportedAt: '2026-11-03T00:00:00Z', pickupDueAt: '2026-11-04T00:00:00Z', overdue: false, late: false, waitingOnClaimant: false, currency: 'SGD', ...over,
});
const pageOf = (...items: StaffClaimSummary[]): ClaimPage => ({ page: 0, size: 20, totalElements: items.length, totalPages: 1, items });
const lists = (ctl: HttpTestingController): TestRequest[] => ctl.match((r) => r.method === 'GET' && r.url === '/api/v1/staff/claims');
const listOf = (ctl: HttpTestingController, queue: string): TestRequest => {
  const found = lists(ctl).filter((r) => r.request.params.get('queue') === queue);
  expect(found.length).toBe(1);
  return found[0];
};

interface Internals {
  reload(quiet: boolean, fromPoll?: boolean): Promise<boolean>;
  setQueue(id: string): void;
}

describe('QueuePage: races and failures', () => {
  let ctl: HttpTestingController;
  let fixture: ComponentFixture<QueuePage>;
  let el: HTMLElement;
  let page: Internals;
  let toast: ToastService;

  beforeEach(async () => {
    ctl = setupStaffTest('OFFICER', ['SG']);
    toast = TestBed.inject(ToastService);
    vi.spyOn(toast, 'error');
    vi.spyOn(toast, 'success');
    fixture = TestBed.createComponent(QueuePage);
    el = fixture.nativeElement;
    page = fixture.componentInstance as unknown as Internals;
    fixture.detectChanges();
    listOf(ctl, 'unassigned').flush(pageOf(row(7)));
    await flush(fixture);
  });
  afterEach(() => {
    fixture.destroy();
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('drops the response of an old request that arrives after a newer one', async () => {
    page.setQueue('mine');
    const mine = listOf(ctl, 'mine');
    page.setQueue('all');
    const all = listOf(ctl, 'all');

    all.flush(pageOf(row(2, { status: 'UNDER_REVIEW' })));
    await flush(fixture);
    mine.flush(pageOf(row(1, { status: 'UNDER_REVIEW' }))); // older, answers last
    await flush(fixture);

    expect(el.querySelector('[data-testid=row-CLM-SG-000002]')).not.toBeNull();
    expect(el.querySelector('[data-testid=row-CLM-SG-000001]')).toBeNull();
  });

  it('a failed background refresh shows "Out of date since HH:MM, retrying", keeps the rows, and the next success clears it', async () => {
    void page.reload(true, true);
    await Promise.resolve();
    listOf(ctl, 'unassigned').flush({ status: 503, code: 'X', title: 'down' }, { status: 503, statusText: 'x' });
    await flush(fixture);
    expect(el.querySelector('[data-testid=out-of-date]')!.textContent).toMatch(/Out of date since \d\d:\d\d, retrying/);
    expect(el.querySelector('[data-testid=row-CLM-SG-000007]')).not.toBeNull();

    void page.reload(true, true);
    await Promise.resolve();
    listOf(ctl, 'unassigned').flush(pageOf(row(7)));
    await flush(fixture);
    expect(el.querySelector('[data-testid=out-of-date]')).toBeNull();
  });

  const pickUp = async () => {
    el.querySelector<HTMLButtonElement>('button[aria-label="Pick up CLM-SG-000007"]')!.click();
    return ctl.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/staff/claims/a7/pick-up');
  };

  it('on 409 says someone else got it, and shows the refreshed list', async () => {
    const post = await pickUp();
    post.flush({ status: 409, code: 'STALE_VERSION', title: 'Stale' }, { status: 409, statusText: 'Conflict' });
    await flush(fixture);
    listOf(ctl, 'unassigned').flush(pageOf(row(8)));
    await flush(fixture);

    expect(toast.error).toHaveBeenCalledWith('CLM-SG-000007 was just picked up by another officer, or changed. The list has been refreshed.');
    expect(el.querySelector('[data-testid=row-CLM-SG-000007]')).toBeNull();
    expect(el.querySelector('[data-testid=row-CLM-SG-000008]')).not.toBeNull();
  });

  it('on 409 with a failed refresh does not claim the list was refreshed', async () => {
    const post = await pickUp();
    post.flush({ status: 409, code: 'STALE_VERSION', title: 'Stale' }, { status: 409, statusText: 'Conflict' });
    await flush(fixture);
    listOf(ctl, 'unassigned').flush({ status: 503, code: 'X', title: 'down' }, { status: 503, statusText: 'x' });
    await flush(fixture);

    const message = vi.mocked(toast.error).mock.calls[0][0];
    expect(message).toContain('was just picked up by another officer');
    expect(message).not.toContain('has been refreshed');
    expect(message).toContain('could not be refreshed');
  });

  it('after a successful pick-up the row is gone even though the refresh fails', async () => {
    const post = await pickUp();
    post.flush({ id: 'a7', version: 6 }, { headers: { ETag: '"6"' } });
    await flush(fixture);
    expect(el.querySelector('[data-testid=row-CLM-SG-000007]')).toBeNull();
    listOf(ctl, 'unassigned').flush({ status: 503, code: 'X', title: 'down' }, { status: 503, statusText: 'x' });
    await flush(fixture);

    expect(el.querySelector('[data-testid=row-CLM-SG-000007]')).toBeNull();
    expect(toast.success).toHaveBeenCalledWith('You picked up CLM-SG-000007. It is now in Mine.');
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('a poll that started before the pick-up succeeded cannot bring the row back', async () => {
    void page.reload(true, true); // read taken while the claim was still unassigned
    await Promise.resolve();
    const oldPoll = listOf(ctl, 'unassigned');
    const post = await pickUp();
    post.flush({ id: 'a7', version: 6 }, { headers: { ETag: '"6"' } });
    await flush(fixture);

    oldPoll.flush(pageOf(row(7)));
    await flush(fixture);
    expect(el.querySelector('[data-testid=row-CLM-SG-000007]')).toBeNull();
    listOf(ctl, 'unassigned').flush(pageOf());
    await flush(fixture);
  });

  it('a failed load for other parameters shows the error, not the previous queue\'s rows under an out-of-date banner', async () => {
    page.setQueue('mine'); // foreground load in flight; the unassigned rows are still on screen
    listOf(ctl, 'mine');
    void page.reload(true, true); // a poll tick for the new tab supersedes it and fails
    listOf(ctl, 'mine').flush({ status: 500, code: 'X', title: 'boom' }, { status: 500, statusText: 'x' });
    await flush(fixture);
    expect(el.querySelector('[data-testid=queue-table]')).toBeNull();
    expect(el.querySelector('[data-testid=out-of-date]')).toBeNull();
    expect(el.textContent).toContain('Could not load claims');
  });
});
