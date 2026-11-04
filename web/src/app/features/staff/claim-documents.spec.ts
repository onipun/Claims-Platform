import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ClaimDocuments } from './claim-documents';
import { CLAIM_ID, setupStaffTest } from './testing';
import type { DocumentChecklist } from './staff.api';

const BASE = `/api/v1/staff/claims/${CLAIM_ID}`;
const CHECKLIST: DocumentChecklist = {
  outstanding: 0,
  items: [{ documentType: 'REPAIR_QUOTE', status: 'UPLOADED', placeholder: false, uploads: [
    { id: 'd1', documentType: 'REPAIR_QUOTE', filename: 'quote.pdf', sizeBytes: 20480, status: 'UPLOADED', uploadedAt: '2026-11-03T01:00:00Z' },
  ] }],
  other: [],
};

describe('ClaimDocuments (staff) download', () => {
  let ctl: HttpTestingController;
  let fixture: ComponentFixture<ClaimDocuments>;

  beforeEach(() => {
    ctl = setupStaffTest('OFFICER', ['SG']);
    fixture = TestBed.createComponent(ClaimDocuments);
    fixture.componentRef.setInput('claimId', CLAIM_ID);
    fixture.componentRef.setInput('checklist', CHECKLIST);
    fixture.detectChanges();
  });
  afterEach(() => {
    ctl.verify();
    fixture.destroy();
    sessionStorage.clear();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('fetches a Blob through the HTTP client, clicks an anchor with the filename, and revokes the URL after 10 s', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    const made: Blob[] = [];
    const revoked: string[] = [];
    vi.spyOn(URL, 'createObjectURL').mockImplementation((b: Blob | MediaSource) => (made.push(b as Blob), 'blob:staff-1'));
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation((u: string) => void revoked.push(u));
    const clicked: { download: string; href: string }[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ download: this.download, href: this.href });
    });

    fixture.nativeElement.querySelector('button[aria-label="Download quote.pdf"]').click();
    await Promise.resolve();
    const req = ctl.expectOne(`${BASE}/documents/d1/content`);
    expect(req.request.responseType).toBe('blob');
    const blob = new Blob(['pdf']);
    req.flush(blob);
    for (let i = 0; i < 10; i++) await Promise.resolve();

    expect(made).toEqual([blob]);
    expect(clicked).toEqual([{ download: 'quote.pdf', href: 'blob:staff-1' }]);
    expect(revoked).toEqual([]);
    vi.advanceTimersByTime(10_000);
    expect(revoked).toEqual(['blob:staff-1']);
  });

  it('encodes ids in the download path', async () => {
    fixture.componentRef.setInput('claimId', 'a/b c');
    fixture.componentRef.setInput('checklist', { ...CHECKLIST, items: [{ ...CHECKLIST.items![0], uploads: [{ ...CHECKLIST.items![0].uploads![0], id: 'x/y' }] }] });
    fixture.detectChanges();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    fixture.nativeElement.querySelector('button[aria-label^="Download"]').click();
    await Promise.resolve();
    ctl.expectOne('/api/v1/staff/claims/a%2Fb%20c/documents/x%2Fy/content').flush(new Blob(['x']));
    await fixture.whenStable();
  });
});

describe('ClaimDocuments (staff) checklist badge', () => {
  const render = (items: DocumentChecklist['items']) => {
    const ctl = setupStaffTest('OFFICER', ['SG']);
    const fixture = TestBed.createComponent(ClaimDocuments);
    fixture.componentRef.setInput('claimId', CLAIM_ID);
    fixture.componentRef.setInput('checklist', { outstanding: 0, items, other: [] } satisfies DocumentChecklist);
    fixture.detectChanges();
    ctl.verify();
    return fixture;
  };
  const up = { id: 'u1', documentType: 'DRIVER_ID', filename: 'a.png', sizeBytes: 70, status: 'UPLOADED' as const, uploadedAt: '2026-11-03T01:00:00Z' };

  it('an indicative requirement that has an upload is not labelled "Not uploaded"', () => {
    const f = render([{ documentType: 'DRIVER_ID', status: 'UPLOADED', placeholder: true, uploads: [up] }]);
    expect(f.nativeElement.textContent).not.toContain('Not uploaded');
    f.destroy();
  });
  it('an indicative requirement with no upload still says "Not uploaded"', () => {
    const f = render([{ documentType: 'DRIVER_ID', status: 'MISSING', placeholder: true, uploads: [] }]);
    expect(f.nativeElement.textContent).toContain('Not uploaded');
    f.destroy();
  });
});
