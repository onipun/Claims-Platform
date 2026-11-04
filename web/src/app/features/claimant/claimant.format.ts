import { formatInstant } from '../../core/util/time';

const LOCAL = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?(?::\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/;

const DATE_OPTS: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', year: 'numeric' };
const TIME_OPTS: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' };

function fmt(d: Date, opts: Intl.DateTimeFormatOptions, zone?: string | null): string {
  try {
    return new Intl.DateTimeFormat('en-GB', { ...opts, timeZone: zone || undefined }).format(d);
  } catch {
    return new Intl.DateTimeFormat('en-GB', opts).format(d);
  }
}

/**
 * Formats a date or date-time string for display.
 * - A date or a local date-time without offset (`2026-03-07T22:30:00`, `2026-03-07`) is already in the
 *   market's wall-clock time: it is shown as written, never shifted into the browser's zone.
 * - A string with an offset (`...Z`) is an instant: it is shown in `zone` (the market's IANA zone) when known.
 */
export function formatWhen(value: string | null | undefined, opts: { zone?: string | null; time?: boolean } = {}): string {
  if (!value) return '';
  const m = LOCAL.exec(value.trim());
  if (!m) return value;
  const [, y, mo, d, hh, mm, off] = m;
  const withTime = opts.time !== false && hh !== undefined;
  const o = withTime ? { ...DATE_OPTS, ...TIME_OPTS } : DATE_OPTS;
  // An instant: name the zone it is shown in, through the one shared formatter.
  if (off) return withTime ? formatInstant(value, opts.zone, '') : fmt(new Date(value), o, opts.zone);
  return fmt(new Date(Date.UTC(+y, +mo - 1, +d, +(hh ?? 0), +(mm ?? 0))), o, 'UTC');
}

export const formatDay = (v: string | null | undefined, zone?: string | null) => formatWhen(v, { zone, time: false });

/** Epoch ms of an ISO instant, or NaN. */
export const instantMs = (v: string | null | undefined): number => (v ? Date.parse(v) : NaN);

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const ALLOWED_UPLOAD_TYPES: readonly string[] = ['application/pdf', 'image/jpeg', 'image/png'];
const ALLOWED_EXT = ['.pdf', '.jpg', '.jpeg', '.png'];

/** Client-side pre-check of an upload; the server still decides. Returns an error message or null. */
export function checkUpload(file: { name: string; size: number; type: string }): string | null {
  const name = file.name.toLowerCase();
  const extOk = ALLOWED_EXT.some((e) => name.endsWith(e));
  const typeOk = file.type === '' || ALLOWED_UPLOAD_TYPES.includes(file.type);
  if (!extOk || !typeOk) return 'This file type is not accepted. Upload a PDF, JPEG or PNG.';
  if (file.size === 0) return 'This file is empty.';
  if (file.size > MAX_UPLOAD_BYTES) return `This file is ${tooLargeSize(file.size)}. The limit is 10 MB.`;
  return null;
}

/** Rounded up, so a file a byte over the limit never reads as "10.0 MB" against a 10 MB limit. */
function tooLargeSize(n: number): string {
  return `${(Math.ceil((n / (1024 * 1024)) * 10) / 10).toFixed(1)} MB`;
}

export function formatBytes(n: number | null | undefined): string {
  if (n == null) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
