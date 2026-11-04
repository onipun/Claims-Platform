import type { Role } from '../app/core/auth/roles';

const b64url = (s: string) => btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** An unsigned JWT-shaped string, for tests only (the client never verifies signatures). */
export function makeToken(opts: { role?: Role; name?: string; markets?: string[]; expInSeconds?: number; sub?: string } = {}): string {
  const payload = {
    sub: opts.sub ?? '00000000-0000-0000-0000-000000000001',
    role: opts.role ?? 'CLAIMANT',
    name: opts.name ?? 'Test User',
    markets: opts.markets ?? ['SG'],
    exp: Math.floor(Date.now() / 1000) + (opts.expInSeconds ?? 900),
  };
  return `${b64url('{"alg":"none"}')}.${b64url(JSON.stringify(payload))}.sig`;
}
