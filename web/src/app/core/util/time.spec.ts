import { formatInstant, formatMarketLocal } from './time';

describe('time formatting', () => {
  it('names the zone an instant is shown in', () => {
    expect(formatInstant('2026-11-03T01:00:00Z', 'Asia/Singapore')).toBe('03 Nov 2026, 09:00 GMT+8');
    expect(formatInstant('2026-11-03T01:00:00Z', 'UTC')).toBe('03 Nov 2026, 01:00 UTC');
  });
  it('shows an incident in the market zone as written', () => {
    expect(formatMarketLocal('2026-10-30T22:15:00', 'Asia/Singapore')).toBe('30 Oct 2026, 22:15 (Asia/Singapore)');
  });
  it('is a dash for nothing and passes unparseable text through', () => {
    expect(formatInstant(null)).toBe('–');
    expect(formatInstant('soon')).toBe('soon');
  });
});
