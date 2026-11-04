import type { ClaimsSchemas } from '../../core/api';

export type ClaimDetail = ClaimsSchemas['ClaimantClaimDetail'];
export type ClaimSummary = ClaimsSchemas['ClaimantClaimSummary'];
export type ClaimPage = ClaimsSchemas['PageResponseClaimantClaimSummary'];
export type UpdatePage = ClaimsSchemas['PageResponseClaimantUpdate'];
export type ClaimUpdate = ClaimsSchemas['ClaimantUpdate'];
export type Policy = ClaimsSchemas['ClaimantPolicy'];
export type Checklist = ClaimsSchemas['DocumentChecklist'];
export type ChecklistItem = ClaimsSchemas['ChecklistItem'];
export type DocumentView = ClaimsSchemas['DocumentView'];
export type SubmitRequest = ClaimsSchemas['SubmitClaimRequest'];
export type Line = NonNullable<ClaimDetail['line']>;
export type ClaimType = NonNullable<ClaimDetail['claimType']>;
export type Status = NonNullable<ClaimDetail['status']>;
export type ReasonCode = NonNullable<ClaimsSchemas['DecisionView']['reasonCode']>;

/** A body plus the entity tag it came with (the claim version, quoted). */
export interface Tagged<T> {
  body: T;
  etag: string | null;
  /** True when the server answered a repeated Idempotency-Key with the original claim (HTTP 200, not 201). */
  replayed?: boolean;
}
