import { STALE_AFTER_MS, ageText, freshness, isUnavailable } from './freshness';

describe('freshness (fixed clock)', () => {
  const NOW = Date.parse('2026-11-04T10:00:00Z');
  it('is fresh within two minutes and stale beyond', () => {
    expect(freshness('2026-11-04T09:58:30Z', NOW)).toEqual({ stale: false, ageSeconds: 90 });
    expect(freshness('2026-11-04T09:58:00Z', NOW).stale).toBe(false); // exactly 2 min: not yet stale
    expect(freshness('2026-11-04T09:57:59Z', NOW).stale).toBe(true);
    expect(STALE_AFTER_MS).toBe(120_000);
  });
  it('treats null, undefined and garbage as stale with unknown age', () => {
    for (const v of [null, undefined, '', 'not a date']) expect(freshness(v, NOW)).toEqual({ stale: true, ageSeconds: null });
  });
  it('never reports a negative age for a timestamp slightly in the future', () => {
    expect(freshness('2026-11-04T10:00:05Z', NOW).ageSeconds).toBe(0);
  });
  it('formats ages and classifies unavailable statuses', () => {
    expect(ageText(null)).toBe('unknown age');
    expect(ageText(45)).toBe('45s ago');
    expect(ageText(300)).toBe('5 min ago');
    expect([0, 502, 503, 504].every(isUnavailable)).toBe(true);
    expect([400, 401, 403, 404, 500].some(isUnavailable)).toBe(false);
  });
});
