import type { ClaimType, Line, ReasonCode, Status } from './claimant.types';

export const LINE_LABEL: Record<Line, string> = { MOTOR: 'Motor', PROPERTY: 'Property' };

export const CLAIM_TYPE_LABEL: Record<ClaimType, string> = {
  OWN_DAMAGE: 'Damage to my own vehicle',
  THIRD_PARTY_DAMAGE: "Damage to someone else's vehicle or property",
  TOTAL_LOSS: 'Total loss',
  WINDSCREEN: 'Windscreen',
  THEFT: 'Theft',
  FIRE: 'Fire',
  WATER_DAMAGE: 'Water damage',
  STORM: 'Storm',
  OTHER: 'Other',
};

/** Claim types valid per line (docs/domain/LIFECYCLE.md, submission rules). */
export const CLAIM_TYPES_BY_LINE: Record<Line, readonly ClaimType[]> = {
  MOTOR: ['OWN_DAMAGE', 'THIRD_PARTY_DAMAGE', 'TOTAL_LOSS', 'WINDSCREEN', 'THEFT'],
  PROPERTY: ['FIRE', 'WATER_DAMAGE', 'STORM', 'THEFT', 'OTHER'],
};

/** What a status means for the person who made the claim. */
export const STATUS_EXPLANATION: Record<Status, string> = {
  SUBMITTED: 'We have received your claim. A claims officer will pick it up shortly.',
  UNDER_REVIEW: 'A claims officer is reviewing your claim. You do not need to do anything unless we ask.',
  AWAITING_INFO: 'We need more information from you before we can continue. Please answer the question below.',
  ASSESSED: 'Your claim has been assessed and a decision is being finalised.',
  APPROVED: 'Your claim has been approved. See the decision below.',
  SETTLED: 'Your claim has been settled and is now closed.',
  REJECTED: 'We were not able to accept your claim. The reason is shown below.',
};

export const REASON_LABEL: Record<ReasonCode, string> = {
  NOT_COVERED: 'Not covered by your policy',
  POLICY_NOT_IN_FORCE: 'Your policy was not in force at the time',
  EXCLUDED_CAUSE: 'The cause of the loss is excluded',
  INSUFFICIENT_EVIDENCE: 'There was not enough evidence',
  BELOW_DEDUCTIBLE: 'The loss is below your deductible',
  OTHER: 'Other reason',
};

export function statusLabel(s: string | null | undefined): string {
  if (!s) return '';
  const t = s.toLowerCase().replace(/_/g, ' ');
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/** `POLICE_REPORT` -> `Police report`. Document types are free text from the checklist configuration. */
export function humanise(code: string | null | undefined): string {
  return statusLabel(code);
}

export function claimTypeLabel(t: string | null | undefined): string {
  return (t && CLAIM_TYPE_LABEL[t as ClaimType]) || humanise(t);
}

export function lineLabel(l: string | null | undefined): string {
  return (l && LINE_LABEL[l as Line]) || humanise(l);
}
