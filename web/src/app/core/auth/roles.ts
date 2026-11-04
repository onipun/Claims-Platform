import type { Role } from '../api';

export type { Role };
export const STAFF_ROLES: readonly Role[] = ['OFFICER', 'MANAGER'];

/** Where a role lands: its own tree. The backend still enforces every call. */
export function homeFor(role: Role | null | undefined): string {
  switch (role) {
    case 'CLAIMANT':
      return '/claimant';
    case 'OFFICER':
    case 'MANAGER':
      return '/staff';
    default:
      return '/login';
  }
}
