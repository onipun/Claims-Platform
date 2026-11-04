import { HttpHeaders, HttpResponse } from '@angular/common/http';

/** The entity tag of a response, exactly as sent (quoted), or null when absent. */
export function etagOf(res: Pick<HttpResponse<unknown>, 'headers'>): string | null {
  return res.headers.get('ETag');
}

/**
 * Headers for an optimistic-concurrency write. Pass the tag exactly as received: the backend sends
 * the claim version quoted and expects it back quoted. A weak validator prefix is stripped because the
 * server compares versions, not bytes. Throws when there is no tag: an update without one must not be
 * sent, or it would overwrite someone else's change.
 */
export function ifMatch(etag: string | null | undefined, extra?: Record<string, string>): HttpHeaders {
  if (!etag) throw new Error('Missing ETag: reload the record before changing it.');
  const tag = etag.startsWith('W/') ? etag.slice(2) : etag;
  return new HttpHeaders({ ...extra, 'If-Match': tag });
}
