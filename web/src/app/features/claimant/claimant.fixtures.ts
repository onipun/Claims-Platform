import type { Checklist, ClaimDetail, Policy } from './claimant.types';

export const MOTOR_POLICY: Policy = {
  id: '11111111-1111-4111-8111-111111111111', policyNumber: 'SG-MTR-0001', line: 'MOTOR', marketCode: 'SG', currency: 'SGD',
  coverageLimit: '50000.00', deductible: '500.00', effectiveFrom: '2026-01-01', effectiveTo: '2026-12-31',
};
export const PROPERTY_POLICY: Policy = {
  id: '22222222-2222-4222-8222-222222222222', policyNumber: 'SG-HOM-0007', line: 'PROPERTY', marketCode: 'SG', currency: 'SGD',
  coverageLimit: '300000.00', deductible: '1000.00', effectiveFrom: '2026-01-01', effectiveTo: '2026-12-31',
};
export const IDR_POLICY: Policy = { ...MOTOR_POLICY, id: '33333333-3333-4333-8333-333333333333', currency: 'IDR', marketCode: 'ID' };

export const CLAIM_ID = '99999999-9999-4999-8999-999999999999';

export function claimDetail(over: Partial<ClaimDetail> = {}): ClaimDetail {
  return {
    id: CLAIM_ID, claimNumber: 'CLM-SG-000042', status: 'AWAITING_INFO', line: 'MOTOR', claimType: 'OWN_DAMAGE', version: 3,
    policyId: MOTOR_POLICY.id, policyNumber: 'SG-MTR-0001', currency: 'SGD', estimate: '1200.50', marketZone: 'Asia/Singapore',
    incidentAtLocal: '2026-03-07T22:30:00', reportedAt: '2026-03-08T01:10:00Z', actionRequired: true, late: false,
    description: 'Hit a barrier.', contactPhone: '+65 8123 4567',
    motor: { vehicleRegistration: 'SGX1234A', driverName: 'Tan Wei', location: 'Orchard Road, Singapore', injury: false },
    openQuestion: { askedAt: '2026-03-09T02:00:00Z', question: 'Please confirm where the vehicle is now.' },
    timeline: [{ at: '2026-03-08T01:10:00Z', toStatus: 'SUBMITTED', message: 'Claim received', type: 'STATUS_CHANGED' }],
    ...over,
  };
}

export const CHECKLIST: Checklist = {
  outstanding: 1,
  items: [
    { documentType: 'POLICE_REPORT', status: 'MISSING', placeholder: false, uploads: [] },
    { documentType: 'REPAIR_QUOTE', status: 'RE_REQUESTED', reviewNote: 'The quote was cut off.', uploads: [
      { id: 'd1', documentType: 'REPAIR_QUOTE', filename: 'quote.pdf', sizeBytes: 20480, status: 'RE_REQUESTED', reviewNote: 'The quote was cut off.', uploadedAt: '2026-03-08T03:00:00Z' },
    ] },
  ],
  other: [],
};
