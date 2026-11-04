/**
 * Shared date-time formatting. Anything shown in a zone says which zone, so two people reading the same
 * screen in different places are never left guessing.
 *   instant:  `03 Nov 2026, 09:00 GMT+8`   (the viewer's browser zone, or `zone` when given)
 *   incident: `30 Oct 2026, 22:15 (Asia/Singapore)`   (market wall-clock time, never shifted)
 */
const INSTANT: Intl.DateTimeFormatOptions = {
  day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZoneName: 'short',
};

export function formatInstant(value: string | number | Date | null | undefined, zone?: string | null, empty = '–'): string {
  if (value === null || value === undefined || value === '') return empty;
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return typeof value === 'string' ? value : empty;
  const render = (timeZone?: string) => new Intl.DateTimeFormat('en-GB', { ...INSTANT, timeZone }).format(date).replace(/,?\s+at\s+/, ', ');
  try {
    return render(zone || undefined);
  } catch {
    return render();
  }
}

const LOCAL = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))/;

/** A market wall-clock time (no offset): shown as written, with the market zone named. */
export function formatMarketLocal(value: string | null | undefined, zone: string | null | undefined, empty = '–'): string {
  if (!value) return empty;
  const m = LOCAL.exec(value);
  if (!m) return value;
  const [, y, mo, d, hh, mm] = m;
  const day = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(+y, +mo - 1, +d)));
  return `${day}, ${hh}:${mm}${zone ? ` (${zone})` : ''}`;
}
