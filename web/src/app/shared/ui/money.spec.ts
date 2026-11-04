import { MoneyPipe, formatMoney } from './money';

describe('money formatting (string in, string out)', () => {
  it('formats SGD with 2 decimals', () => {
    expect(formatMoney('1234.50', 'SGD')).toBe('SGD 1,234.50');
  });
  it('formats IDR with 0 decimals', () => {
    expect(formatMoney('150000', 'IDR')).toBe('IDR 150,000');
    expect(formatMoney('150000.00', 'IDR')).toBe('IDR 150,000');
  });
  it('pads missing decimals to the currency scale', () => {
    expect(formatMoney('10', 'SGD')).toBe('SGD 10.00');
    expect(formatMoney('10.5', 'MYR')).toBe('MYR 10.50');
  });
  it('does not round or lose precision on a large value a double cannot hold', () => {
    expect(formatMoney('12345678901234.56', 'SGD')).toBe('SGD 12,345,678,901,234.56');
    expect(formatMoney('12345678901234567890.12', 'SGD')).toBe('SGD 12,345,678,901,234,567,890.12');
  });
  it('never rounds away a non-zero digit beyond the scale', () => {
    expect(formatMoney('100.005', 'SGD')).toBe('SGD 100.005');
    expect(formatMoney('150000.50', 'IDR')).toBe('IDR 150,000.50');
  });
  it('handles negatives, zero and leading zeros', () => {
    expect(formatMoney('-5.5', 'SGD')).toBe('SGD -5.50'.replace(' ', ' '));
    expect(formatMoney('-0.00', 'SGD')).toBe('SGD 0.00');
    expect(formatMoney('007.10', 'SGD')).toBe('SGD 7.10');
  });
  it('returns unparseable input unchanged and a dash for missing', () => {
    expect(formatMoney('1e9', 'SGD')).toBe('1e9');
    expect(formatMoney(null, 'SGD')).toBe('—');
  });
  it('pipe delegates', () => {
    expect(new MoneyPipe().transform('1234.50', 'SGD')).toBe('SGD 1,234.50');
  });
});
