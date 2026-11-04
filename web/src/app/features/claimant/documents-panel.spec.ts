import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ProblemError } from '../../core/api/problem-error';
import { CHECKLIST, CLAIM_ID } from './claimant.fixtures';
import { DocumentsPanel, uploadErrorText } from './documents-panel';

const DOCS = `/api/v1/claimant/claims/${CLAIM_ID}/documents`;

describe('DocumentsPanel', () => {
  let fixture: ComponentFixture<DocumentsPanel>;
  let el: HTMLElement;
  let ctl: HttpTestingController;

  const settle = async () => {
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r));
    fixture.detectChanges();
  };
  const choose = async (testid: string, file: File) => {
    const input = el.querySelector<HTMLInputElement>(`[data-testid="${testid}"] input[type=file]`)!;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle();
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    fixture = TestBed.createComponent(DocumentsPanel);
    el = fixture.nativeElement;
    ctl = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('claimId', CLAIM_ID);
    fixture.componentRef.setInput('checklist', CHECKLIST);
    fixture.detectChanges();
  });
  afterEach(() => ctl.verify());

  it('blocks a wrong type or oversize file before any request is made', async () => {
    await choose('doc-POLICE_REPORT', new File(['x'], 'virus.exe', { type: 'application/x-msdownload' }));
    expect(el.querySelector('[data-testid=doc-POLICE_REPORT] [data-testid=upload-error]')?.textContent).toMatch(/not accepted/);
    const big = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.pdf', { type: 'application/pdf' });
    await choose('doc-POLICE_REPORT', big);
    expect(el.querySelector('[data-testid=doc-POLICE_REPORT] [data-testid=upload-error]')?.textContent).toMatch(/10 MB/);
    ctl.expectNone((r) => r.url === DOCS);
  });

  it('uploads a valid file as multipart with the document type, and emits', async () => {
    let emitted = 0;
    fixture.componentInstance.uploaded.subscribe(() => emitted++);
    await choose('doc-POLICE_REPORT', new File(['%PDF'], 'report.pdf', { type: 'application/pdf' }));
    const req = ctl.expectOne((r) => r.url === DOCS && r.method === 'POST');
    expect(req.request.params.get('documentType')).toBe('POLICE_REPORT');
    expect(req.request.body instanceof FormData).toBe(true);
    req.flush({ id: 'd9', filename: 'report.pdf', status: 'UPLOADED' });
    await settle();
    expect(emitted).toBe(1);
  });

  it('shows the server message for 413 and 415', async () => {
    await choose('doc-POLICE_REPORT', new File(['%PDF'], 'report.pdf', { type: 'application/pdf' }));
    ctl.expectOne((r) => r.url === DOCS).flush({ status: 413, code: 'PAYLOAD_TOO_LARGE', title: 'Too large' }, { status: 413, statusText: 'Payload Too Large' });
    await settle();
    expect(el.querySelector('[data-testid=doc-POLICE_REPORT] [data-testid=upload-error]')?.textContent).toMatch(/too large/);
    expect(uploadErrorText(new ProblemError({ status: 415, code: 'X', title: 'x' }))).toMatch(/PDF, JPEG or PNG/);
  });

  it('downloads through the HTTP client as a Blob: clicks an anchor with the filename and revokes the URL', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    try {
      const made: Blob[] = [];
      const revoked: string[] = [];
      vi.spyOn(URL, 'createObjectURL').mockImplementation((b: Blob | MediaSource) => (made.push(b as Blob), 'blob:claimant-1'));
      vi.spyOn(URL, 'revokeObjectURL').mockImplementation((u: string) => void revoked.push(u));
      const clicked: { download: string; href: string }[] = [];
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        clicked.push({ download: this.download, href: this.href });
      });
      const btn = Array.from(el.querySelectorAll('button')).find((b) => /Download/.test(b.textContent ?? ''))!;
      btn.click();
      await Promise.resolve();
      const req = ctl.expectOne(`${DOCS}/d1/content`);
      expect(req.request.responseType).toBe('blob');
      const blob = new Blob(['pdf']);
      req.flush(blob);
      for (let i = 0; i < 10; i++) await Promise.resolve();
      expect(made).toEqual([blob]);
      expect(clicked).toEqual([{ download: 'quote.pdf', href: 'blob:claimant-1' }]);
      expect(revoked).toEqual([]);
      vi.advanceTimersByTime(10_000);
      expect(revoked).toEqual(['blob:claimant-1']);
    } finally {
      vi.useRealTimers();
      vi.restoreAllMocks();
    }
  });
});
