import type { components as ClaimsComponents } from './generated/claims';
import type { components as InsightsComponents } from './generated/insights';

export type { paths as ClaimsPaths } from './generated/claims';
export type { paths as InsightsPaths } from './generated/insights';

/** Schemas of claims-service (generated; `npm run gen:api`). */
export type ClaimsSchemas = ClaimsComponents['schemas'];
export type InsightsSchemas = InsightsComponents['schemas'];

export type LoginRequest = ClaimsSchemas['LoginRequest'];
export type LoginResponse = ClaimsSchemas['LoginResponse'];
export type UserView = ClaimsSchemas['UserView'];
export type Role = NonNullable<UserView['role']>;
