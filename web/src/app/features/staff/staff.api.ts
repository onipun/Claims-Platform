import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { ClaimsSchemas, InsightsSchemas } from '../../core/api';
import { etagOf, ifMatch } from '../../core/api/etag';
import { EMPTY_RESPONSE, ProblemError } from '../../core/api/problem-error';

export type StaffClaimSummary = ClaimsSchemas['StaffClaimSummary'];
export type StaffClaimDetail = ClaimsSchemas['StaffClaimDetail'];
export type StaffFinancials = ClaimsSchemas['StaffFinancials'];
export type StaffOfficer = ClaimsSchemas['StaffOfficer'];
export type DocumentChecklist = ClaimsSchemas['DocumentChecklist'];
export type DocumentView = ClaimsSchemas['DocumentView'];
export type TimelineEntry = ClaimsSchemas['TimelineEntry'];
export type ClaimPage = ClaimsSchemas['PageResponseStaffClaimSummary'];
export type RejectionRequest = ClaimsSchemas['RejectionRequest'];
export type ClaimStatus = NonNullable<StaffClaimSummary['status']>;
export type RejectReason = RejectionRequest['reason'];

export type Queue = 'unassigned' | 'mine' | 'all';
export const CLAIM_STATUSES: readonly ClaimStatus[] = [
  'SUBMITTED', 'UNDER_REVIEW', 'AWAITING_INFO', 'ASSESSED', 'APPROVED', 'SETTLED', 'REJECTED',
];
export const REJECT_REASONS: readonly { value: RejectReason; label: string }[] = [
  { value: 'NOT_COVERED', label: 'Not covered' },
  { value: 'POLICY_NOT_IN_FORCE', label: 'Policy not in force' },
  { value: 'EXCLUDED_CAUSE', label: 'Excluded cause' },
  { value: 'INSUFFICIENT_EVIDENCE', label: 'Insufficient evidence' },
  { value: 'BELOW_DEDUCTIBLE', label: 'Below deductible' },
  { value: 'OTHER', label: 'Other' },
];

/** A body-carrying command. Each maps to one POST under /api/v1/staff/claims/{id}. */
export type Command =
  | { kind: 'pick-up' }
  | { kind: 'assign'; body: ClaimsSchemas['Assign'] }
  | { kind: 'notes'; body: ClaimsSchemas['Note'] }
  | { kind: 'info-requests'; body: ClaimsSchemas['InfoRequestBody'] }
  | { kind: 'assessment'; body: ClaimsSchemas['Assessment'] }
  | { kind: 'approval'; body: ClaimsSchemas['Approval'] }
  | { kind: 'rejection'; body: RejectionRequest }
  | { kind: 'settlement'; body: ClaimsSchemas['Settlement'] };

export interface Versioned<T> {
  data: T;
  /** The ETag exactly as sent by the server (quoted). */
  etag: string | null;
}

const BASE = '/api/v1/staff';
const enc = encodeURIComponent;

/** The quoted entity tag for a claim version, as the backend expects it back in If-Match. */
export const etagForVersion = (version: number | undefined): string | null => (version == null ? null : `"${version}"`);

@Injectable({ providedIn: 'root' })
export class StaffApi {
  private readonly http = inject(HttpClient);

  /** Runs a request and normalises any failure into a ProblemError. */
  private async run<T>(req: Promise<T>): Promise<T> {
    try {
      return await req;
    } catch (e) {
      throw ProblemError.from(e);
    }
  }

  /** GET /api/v1/staff/claims */
  listClaims(q: { queue: Queue; status?: ClaimStatus | ''; page: number; size: number }): Promise<ClaimPage> {
    const params: Record<string, string | number> = { queue: q.queue, page: q.page, size: q.size };
    if (q.status) params['status'] = q.status;
    return this.run(firstValueFrom(this.http.get<ClaimPage>(`${BASE}/claims`, { params })));
  }

  /** GET /api/v1/staff/claims/{id}; the ETag is kept for the next command. */
  async getClaim(id: string): Promise<Versioned<StaffClaimDetail>> {
    const res = await this.run(firstValueFrom(this.http.get<StaffClaimDetail>(`${BASE}/claims/${enc(id)}`, { observe: 'response' })));
    return this.versioned(res);
  }

  /** GET /api/v1/staff/claims/{id}/financials */
  financials(id: string): Promise<StaffFinancials> {
    return this.run(firstValueFrom(this.http.get<StaffFinancials>(`${BASE}/claims/${enc(id)}/financials`)));
  }

  /** GET /api/v1/staff/claims/{id}/documents */
  documents(id: string): Promise<DocumentChecklist> {
    return this.run(firstValueFrom(this.http.get<DocumentChecklist>(`${BASE}/claims/${enc(id)}/documents`)));
  }

  /** GET /api/v1/staff/officers */
  officers(): Promise<StaffOfficer[]> {
    return this.run(firstValueFrom(this.http.get<StaffOfficer[]>(`${BASE}/officers`)));
  }

  /**
   * POST pick-up | assign | notes | info-requests | assessment | approval | rejection | settlement.
   * Always sends If-Match with the ETag the caller last read; throws (without sending) if there is none.
   */
  async command(id: string, etag: string | null, cmd: Command): Promise<Versioned<StaffClaimDetail>> {
    const body = cmd.kind === 'pick-up' ? null : cmd.body;
    const res = await this.run(
      firstValueFrom(
        this.http.post<StaffClaimDetail>(`${BASE}/claims/${enc(id)}/${cmd.kind}`, body, {
          headers: ifMatch(etag),
          observe: 'response',
        }),
      ),
    );
    return this.versioned(res);
  }

  /** POST /api/v1/staff/claims/{id}/documents/{docId}/review (no If-Match in the contract). */
  reviewDocument(id: string, docId: string, body: ClaimsSchemas['ReviewRequest']): Promise<DocumentView> {
    return this.run(firstValueFrom(this.http.post<DocumentView>(`${BASE}/claims/${enc(id)}/documents/${enc(docId)}/review`, body)));
  }

  /** GET /api/v1/staff/claims/{id}/documents/{docId}/content as a Blob (bearer added by the interceptor). */
  downloadDocument(id: string, docId: string): Promise<Blob> {
    return this.run(firstValueFrom(this.http.get(`${BASE}/claims/${enc(id)}/documents/${enc(docId)}/content`, { responseType: 'blob' })));
  }

  private versioned(res: HttpResponse<StaffClaimDetail>): Versioned<StaffClaimDetail> {
    const data = res.body;
    if (data === null || data === undefined) {
      throw new ProblemError({ status: res.status, code: EMPTY_RESPONSE, title: 'The server sent an empty response' });
    }
    return { data, etag: etagOf(res) ?? etagForVersion(data.version) };
  }
}

// ---- Insights (insights-service, read model) ----
export type ExposureResponse = InsightsSchemas['ExposureResponse'];
export type WorkloadResponse = InsightsSchemas['WorkloadResponse'];
export type PerformanceResponse = InsightsSchemas['PerformanceResponse'];

@Injectable({ providedIn: 'root' })
export class InsightsApi {
  private readonly http = inject(HttpClient);
  private async run<T>(req: Promise<T>): Promise<T> {
    try {
      return await req;
    } catch (e) {
      throw ProblemError.from(e);
    }
  }
  private p(markets: readonly string[], extra: Record<string, number> = {}) {
    return { params: { ...extra, ...(markets.length ? { market: [...markets] } : {}) } };
  }
  /** GET /api/v1/insights/exposure */
  exposure(markets: readonly string[]): Promise<ExposureResponse> {
    return this.run(firstValueFrom(this.http.get<ExposureResponse>('/api/v1/insights/exposure', this.p(markets))));
  }
  /** GET /api/v1/insights/workload */
  workload(markets: readonly string[]): Promise<WorkloadResponse> {
    return this.run(firstValueFrom(this.http.get<WorkloadResponse>('/api/v1/insights/workload', this.p(markets))));
  }
  /** GET /api/v1/insights/performance?days= */
  performance(markets: readonly string[], days: number): Promise<PerformanceResponse> {
    return this.run(firstValueFrom(this.http.get<PerformanceResponse>('/api/v1/insights/performance', this.p(markets, { days }))));
  }
}
