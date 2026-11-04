import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { etagOf, ifMatch } from '../../core/api/etag';
import { EMPTY_RESPONSE, ProblemError } from '../../core/api/problem-error';
import type { Checklist, ClaimDetail, ClaimPage, DocumentView, Policy, SubmitRequest, Tagged, UpdatePage } from './claimant.types';

const BASE = '/api/v1/claimant';

/** A response body that must be there: a 2xx with no body is an error, not an empty claim. */
function bodyOf<T>(r: HttpResponse<T>): T {
  if (r.body === null || r.body === undefined) {
    throw new ProblemError({ status: r.status, code: EMPTY_RESPONSE, title: 'The server sent an empty response' });
  }
  return r.body;
}

/** Every claimant API call. Failures surface as ProblemError. */
@Injectable({ providedIn: 'root' })
export class ClaimantApi {
  private readonly http = inject(HttpClient);

  private async run<T>(req: Promise<T>): Promise<T> {
    try {
      return await req;
    } catch (e) {
      throw ProblemError.from(e);
    }
  }

  /** GET /api/v1/claimant/claims */
  listClaims(page = 0, size = 10): Promise<ClaimPage> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.run(firstValueFrom(this.http.get<ClaimPage>(`${BASE}/claims`, { params })));
  }

  /** POST /api/v1/claimant/claims, with the caller's Idempotency-Key. 201 = created, 200 = original returned. */
  submit(body: SubmitRequest, idempotencyKey: string): Promise<Tagged<ClaimDetail>> {
    const headers = new HttpHeaders({ 'Idempotency-Key': idempotencyKey });
    return this.run(
      firstValueFrom(this.http.post<ClaimDetail>(`${BASE}/claims`, body, { headers, observe: 'response' })).then((r) => ({
        body: bodyOf(r),
        etag: etagOf(r),
        replayed: r.status === 200,
      })),
    );
  }

  /** GET /api/v1/claimant/claims/{id}; the ETag is the version to send back as If-Match. */
  getClaim(id: string): Promise<Tagged<ClaimDetail>> {
    return this.run(
      firstValueFrom(this.http.get<ClaimDetail>(`${BASE}/claims/${encodeURIComponent(id)}`, { observe: 'response' })).then(
        (r) => ({ body: bodyOf(r), etag: etagOf(r) }),
      ),
    );
  }

  /** POST /api/v1/claimant/claims/{id}/info-responses with If-Match. */
  answer(id: string, answer: string, etag: string | null): Promise<Tagged<ClaimDetail>> {
    return this.run(
      Promise.resolve().then(() =>
        firstValueFrom(
          this.http.post<ClaimDetail>(`${BASE}/claims/${encodeURIComponent(id)}/info-responses`, { answer }, {
            headers: ifMatch(etag),
            observe: 'response',
          }),
        ).then((r: HttpResponse<ClaimDetail>) => ({ body: bodyOf(r), etag: etagOf(r) })),
      ),
    );
  }

  /** GET /api/v1/claimant/claims/{id}/documents */
  checklist(id: string): Promise<Checklist> {
    return this.run(firstValueFrom(this.http.get<Checklist>(`${BASE}/claims/${encodeURIComponent(id)}/documents`)));
  }

  /** POST /api/v1/claimant/claims/{id}/documents?documentType= (multipart `file`). */
  upload(id: string, documentType: string, file: File): Promise<DocumentView> {
    const form = new FormData();
    form.append('file', file, file.name);
    const params = new HttpParams().set('documentType', documentType);
    return this.run(firstValueFrom(this.http.post<DocumentView>(`${BASE}/claims/${encodeURIComponent(id)}/documents`, form, { params })));
  }

  /** GET /api/v1/claimant/claims/{id}/documents/{docId}/content as a Blob, through the HTTP client so the bearer token is sent. */
  downloadDocument(id: string, docId: string): Promise<Blob> {
    return this.run(
      firstValueFrom(
        this.http.get(`${BASE}/claims/${encodeURIComponent(id)}/documents/${encodeURIComponent(docId)}/content`, { responseType: 'blob' }),
      ),
    );
  }

  /** GET /api/v1/claimant/policies */
  policies(): Promise<Policy[]> {
    return this.run(firstValueFrom(this.http.get<Policy[]>(`${BASE}/policies`)));
  }

  /** GET /api/v1/claimant/updates */
  updates(page = 0, size = 20): Promise<UpdatePage> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.run(firstValueFrom(this.http.get<UpdatePage>(`${BASE}/updates`, { params })));
  }
}
