import { HttpHeaders, HttpResponse } from '@angular/common/http';
import { etagOf, ifMatch } from './etag';

describe('etag helpers', () => {
  it('reads the ETag header as sent', () => {
    const res = new HttpResponse({ headers: new HttpHeaders({ ETag: '"7"' }) });
    expect(etagOf(res)).toBe('"7"');
    expect(etagOf(new HttpResponse())).toBeNull();
  });
  it('builds If-Match from a tag, merging extra headers', () => {
    const h = ifMatch('"7"', { 'Idempotency-Key': 'k1' });
    expect(h.get('If-Match')).toBe('"7"');
    expect(h.get('Idempotency-Key')).toBe('k1');
  });
  it('strips a weak prefix and refuses a missing tag', () => {
    expect(ifMatch('W/"7"').get('If-Match')).toBe('"7"');
    expect(() => ifMatch(null)).toThrow();
  });
});
