import { EMPTY_CLAIM_FORM, IdempotencyGuard, buildRequest, serverFieldToControl, validateClaim } from './claim-form';
import type { ClaimFormValue } from './claim-form';
import { IDR_POLICY, MOTOR_POLICY, PROPERTY_POLICY } from './claimant.fixtures';
import { checkUpload, formatWhen } from './claimant.format';

const NOW = new Date('2026-06-01T12:00:00');
const base: ClaimFormValue = { ...EMPTY_CLAIM_FORM, claimType: 'OWN_DAMAGE', incidentDate: '2026-05-30', incidentTime: '09:15', estimate: '1200.50' };
const motor: ClaimFormValue = { ...base, vehicleRegistration: 'SGX1234A', driverName: 'Tan Wei', location: 'Orchard Road' };
const property: ClaimFormValue = { ...EMPTY_CLAIM_FORM, claimType: 'WATER_DAMAGE', incidentDate: '2026-05-30', incidentTime: '09:15', estimate: '800', address: '1 Main St', cause: 'Burst pipe' };

describe('validateClaim', () => {
  it('accepts a complete motor claim and a complete property claim', () => {
    expect(validateClaim(motor, MOTOR_POLICY, NOW)).toEqual({});
    expect(validateClaim(property, PROPERTY_POLICY, NOW)).toEqual({});
  });

  it('motor requires vehicle registration, driver and accident location, not address or cause', () => {
    const e = validateClaim(base, MOTOR_POLICY, NOW);
    expect(Object.keys(e).sort()).toEqual(['driverName', 'location', 'vehicleRegistration']);
  });

  it('R12: accident location is required on motor, trimmed, at most 200 characters, and never asked on property', () => {
    expect(validateClaim({ ...motor, location: '   ' }, MOTOR_POLICY, NOW).location).toBeTruthy();
    expect(validateClaim({ ...motor, location: 'x'.repeat(200) }, MOTOR_POLICY, NOW).location).toBeUndefined();
    expect(validateClaim({ ...motor, location: 'x'.repeat(201) }, MOTOR_POLICY, NOW).location).toMatch(/at most 200/);
    expect(validateClaim(property, PROPERTY_POLICY, NOW).location).toBeUndefined();
  });

  it('property requires address and cause, not vehicle or driver', () => {
    const e = validateClaim({ ...property, address: ' ', cause: '' }, PROPERTY_POLICY, NOW);
    expect(Object.keys(e).sort()).toEqual(['address', 'cause']);
  });

  it('rejects a claim type that belongs to the other line', () => {
    expect(validateClaim({ ...motor, claimType: 'FIRE' }, MOTOR_POLICY, NOW).claimType).toBeTruthy();
    expect(validateClaim({ ...property, claimType: 'WINDSCREEN' }, PROPERTY_POLICY, NOW).claimType).toBeTruthy();
    expect(validateClaim({ ...motor, claimType: 'THEFT' }, MOTOR_POLICY, NOW).claimType).toBeUndefined();
    expect(validateClaim({ ...property, claimType: 'THEFT' }, PROPERTY_POLICY, NOW).claimType).toBeUndefined();
  });

  it('rejects a future incident but tolerates device-clock skew inside 14 hours', () => {
    expect(validateClaim({ ...motor, incidentDate: '2026-06-03' }, MOTOR_POLICY, NOW).incidentDate).toMatch(/future/);
    expect(validateClaim({ ...motor, incidentDate: '2026-06-01', incidentTime: '20:00' }, MOTOR_POLICY, NOW).incidentDate).toBeUndefined();
  });

  it('rejects an incident outside the policy dates', () => {
    expect(validateClaim({ ...motor, incidentDate: '2025-12-31' }, MOTOR_POLICY, NOW).incidentDate).toMatch(/before your policy/);
  });

  it('estimate: decimals by currency, never negative', () => {
    expect(validateClaim({ ...motor, estimate: '12.345' }, MOTOR_POLICY, NOW).estimate).toBeTruthy();
    expect(validateClaim({ ...motor, estimate: '-5' }, MOTOR_POLICY, NOW).estimate).toBeTruthy();
    expect(validateClaim({ ...motor, estimate: '' }, MOTOR_POLICY, NOW).estimate).toBeTruthy();
    expect(validateClaim({ ...motor, estimate: '0' }, MOTOR_POLICY, NOW).estimate).toBeUndefined();
    expect(validateClaim({ ...motor, estimate: '150000.50' }, IDR_POLICY, NOW).estimate).toBeTruthy();
    expect(validateClaim({ ...motor, estimate: '150000' }, IDR_POLICY, NOW).estimate).toBeUndefined();
  });

  it('enforces field length limits', () => {
    expect(validateClaim({ ...motor, contactPhone: 'x'.repeat(33) }, MOTOR_POLICY, NOW).contactPhone).toBeTruthy();
    expect(validateClaim({ ...motor, lateReason: 'x'.repeat(1001) }, MOTOR_POLICY, NOW).lateReason).toBeTruthy();
  });
});

describe('buildRequest', () => {
  it('sends only motor details for a motor policy and keeps the estimate a string', () => {
    const r = buildRequest({ ...motor, injury: true, injuryDescription: 'Bruising', hasThirdParty: true, tpName: 'Lim' }, MOTOR_POLICY);
    expect(r.estimate).toBe('1200.50');
    expect(r.incidentAtLocal).toBe('2026-05-30T09:15:00');
    expect(r.line).toBe('MOTOR');
    expect(r.property).toBeUndefined();
    expect(r.motor?.thirdParty?.name).toBe('Lim');
    expect(r.motor?.injury).toBe(true);
    expect(r.motor?.location).toBe('Orchard Road');
  });

  it('omits third party when not involved, and property details for a property policy', () => {
    expect(buildRequest({ ...motor, tpName: 'ignored' }, MOTOR_POLICY).motor?.thirdParty).toBeUndefined();
    const p = buildRequest(property, PROPERTY_POLICY);
    expect(p.motor).toBeUndefined();
    expect(p.property).toEqual({ address: '1 Main St', cause: 'Burst pipe', occupancy: undefined });
  });
});

describe('serverFieldToControl', () => {
  it('maps the backend field paths to form controls', () => {
    expect(serverFieldToControl('lossDetails.vehicleRegistration')).toBe('vehicleRegistration');
    expect(serverFieldToControl('motor.thirdParty.name')).toBe('tpName');
    expect(serverFieldToControl('incidentAtLocal')).toBe('incidentDate');
    expect(serverFieldToControl('lossDetails.location')).toBe('location');
    expect(serverFieldToControl('motor.location')).toBe('location');
    expect(serverFieldToControl('nope')).toBeNull();
  });
});

describe('IdempotencyGuard', () => {
  it('keeps the key while the body is unchanged, takes a new one on edit, and after reset', () => {
    let n = 0;
    const g = new IdempotencyGuard(() => `k${++n}`);
    expect(g.keyFor({ a: 1 })).toBe('k1');
    expect(g.keyFor({ a: 1 })).toBe('k1');
    expect(g.keyFor({ a: 2 })).toBe('k2');
    g.reset();
    expect(g.keyFor({ a: 2 })).toBe('k3');
  });
});

describe('upload pre-checks', () => {
  it('a file one byte over the limit does not read as "10.0 MB" against a 10 MB limit', () => {
    const msg = checkUpload({ name: 'a.pdf', size: 10 * 1024 * 1024 + 1, type: 'application/pdf' });
    expect(msg).toBe('This file is 10.1 MB. The limit is 10 MB.');
  });
  const f = (name: string, size: number, type: string) => ({ name, size, type });
  it('accepts PDF, JPEG, PNG up to 10 MB', () => {
    expect(checkUpload(f('a.pdf', 1000, 'application/pdf'))).toBeNull();
    expect(checkUpload(f('a.JPG', 1000, 'image/jpeg'))).toBeNull();
    expect(checkUpload(f('a.png', 10 * 1024 * 1024, 'image/png'))).toBeNull();
  });
  it('rejects wrong type, wrong extension, empty and oversize files', () => {
    expect(checkUpload(f('a.exe', 1000, 'application/x-msdownload'))).toMatch(/not accepted/);
    expect(checkUpload(f('a.pdf', 1000, 'text/html'))).toMatch(/not accepted/);
    expect(checkUpload(f('a.txt', 1000, 'application/pdf'))).toMatch(/not accepted/);
    expect(checkUpload(f('a.pdf', 0, 'application/pdf'))).toMatch(/empty/);
    expect(checkUpload(f('a.pdf', 10 * 1024 * 1024 + 1, 'application/pdf'))).toMatch(/10 MB/);
  });
});

describe('formatWhen', () => {
  it('shows a local date-time as written, in the market wall clock', () => {
    expect(formatWhen('2026-03-07T22:30:00')).toMatch(/7 Mar 2026/);
    expect(formatWhen('2026-03-07T22:30:00')).toMatch(/22:30/);
  });
  it('shows an instant in the market zone', () => {
    // 2026-03-07T22:30Z is 06:30 on 8 March in Singapore (UTC+8).
    expect(formatWhen('2026-03-07T22:30:00Z', { zone: 'Asia/Singapore' })).toMatch(/8 Mar 2026.*06:30/);
  });
});
