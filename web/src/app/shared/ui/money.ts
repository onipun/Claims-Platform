import { Pipe, PipeTransform } from '@angular/core';

/** Minor-unit digits per currency. Anything unlisted uses 2. */
const SCALE: Record<string, number> = { IDR: 0, JPY: 0, KRW: 0, VND: 0 };
const AMOUNT = /^([+-])?(\d+)(?:\.(\d+))?$/;

/**
 * Formats a decimal string with a currency code, without ever converting to a float:
 * `"1234.50","SGD"` -> `SGD 1,234.50`; `"150000","IDR"` -> `IDR 150,000`;
 * `"12345678901234.56","SGD"` -> `SGD 12,345,678,901,234.56`.
 * Digits are only padded (to the currency's scale) or dropped when everything beyond the scale is zero;
 * if any digit beyond the scale is non-zero the fraction is shown in full, never rounded. Unparseable input is returned unchanged rather than guessed at.
 */
export function formatMoney(amount: string | null | undefined, currency: string | null | undefined): string {
  if (amount == null || amount === '') return '—';
  const m = AMOUNT.exec(amount.trim());
  if (!m) return amount;
  const [, sign, intRaw, fracRaw = ''] = m;
  const scale = SCALE[(currency ?? '').toUpperCase()] ?? 2;
  let frac = fracRaw;
  if (/^0*$/.test(frac.slice(scale))) frac = frac.slice(0, scale);
  frac = frac.padEnd(scale, '0');
  const int = intRaw.replace(/^0+(?=\d)/, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const negative = sign === '-' && /[1-9]/.test(intRaw + fracRaw);
  return `${currency ? currency.toUpperCase() + ' ' : ''}${negative ? '-' : ''}${int}${frac ? '.' + frac : ''}`;
}

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(amount: string | null | undefined, currency: string | null | undefined): string {
    return formatMoney(amount, currency);
  }
}
