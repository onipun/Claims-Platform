/** Display helpers for the staff screens. Pure, so they are testable with a fixed clock. */

export const label = (v: string | null | undefined): string =>
  v ? v.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase()) : '–';

/** "3d 4h" / "5h" / "12m" between an instant and `now`. */
export function ageOf(from: string | null | undefined, now: number): string {
  if (!from) return '–';
  const t = Date.parse(from);
  if (Number.isNaN(t)) return '–';
  const mins = Math.max(0, Math.floor((now - t) / 60000));
  const d = Math.floor(mins / 1440);
  const h = Math.floor((mins % 1440) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h`;
  return `${mins}m`;
}

import { formatInstant, formatMarketLocal } from '../../core/util/time';

const HAS_OFFSET = /(Z|[+-]\d{2}:?\d{2})$/;

/**
 * A claim time in the market's zone, zone named: an instant becomes `03 Nov 2026, 09:00 GMT+8`; a value with no
 * offset is already market wall-clock time and is shown as written, as `30 Oct 2026, 22:15 (Asia/Singapore)`.
 */
export function formatLocal(value: string | null | undefined, zone: string | null | undefined): string {
  if (!value) return '–';
  return HAS_OFFSET.test(value) ? formatInstant(value, zone) : formatMarketLocal(value, zone);
}

/** A date-time in the viewer's browser zone, zone named: `03 Nov 2026, 09:00 GMT+8`. */
export function formatWhen(value: string | null | undefined): string {
  return formatInstant(value);
}

export function formatBytes(n: number | null | undefined): string {
  if (n == null) return '–';
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1048576).toFixed(1)} MB`;
}

/** "–" for null/undefined (never 0). */
export function num(v: number | null | undefined, digits = 0): string {
  return v == null || Number.isNaN(v) ? '–' : v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}
export function hours(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return '–';
  return v >= 48 ? `${(v / 24).toFixed(1)} d` : `${v.toFixed(1)} h`;
}
export function percent(v: number | null | undefined): string {
  return v == null || Number.isNaN(v) ? '–' : `${(v * 100).toFixed(0)}%`;
}

/** Due-time text and overdue state relative to `now`. */
export function dueIn(due: string | null | undefined, now: number): { text: string; overdue: boolean } {
  if (!due) return { text: '–', overdue: false };
  const t = Date.parse(due);
  if (Number.isNaN(t)) return { text: '–', overdue: false };
  const diff = t - now;
  const abs = Math.abs(diff);
  const d = Math.floor(abs / 86400000);
  const h = Math.floor((abs % 86400000) / 3600000);
  const span = d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h` : `${Math.max(1, Math.floor(abs / 60000))}m`;
  return diff < 0 ? { text: `${span} overdue`, overdue: true } : { text: `in ${span}`, overdue: false };
}
