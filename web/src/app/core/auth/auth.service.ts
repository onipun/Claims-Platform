import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import type { LoginRequest, LoginResponse } from '../api';
import { ProblemError } from '../api/problem-error';
import type { Role } from './roles';

export const SESSION_KEY = 'cp.session';
const ROLES: readonly Role[] = ['CLAIMANT', 'OFFICER', 'MANAGER'];

export interface Session {
  token: string;
  /** The user's id (`sub`); empty when the token has none. Per-user browser state is keyed by it. */
  userId: string;
  name: string;
  role: Role;
  markets: readonly string[];
  /** Epoch milliseconds. */
  expiresAt: number;
}

/** Decodes (does NOT verify) a JWT payload. Verification is the backend's job on every call. */
export function decodeToken(token: string): Session | null {
  try {
    const part = token.split('.')[1];
    if (!part) return null;
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(b64.padEnd(Math.ceil(b64.length / 4) * 4, '=')), (c) => c.charCodeAt(0));
    const p = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
    const role = p['role'] as Role;
    if (!ROLES.includes(role) || typeof p['exp'] !== 'number') return null;
    const markets = Array.isArray(p['markets']) ? (p['markets'] as unknown[]).filter((m) => typeof m === 'string') : [];
    return {
      token,
      userId: typeof p['sub'] === 'string' ? p['sub'] : '',
      name: typeof p['name'] === 'string' ? p['name'] : '',
      role,
      markets: markets as string[],
      expiresAt: p['exp'] * 1000,
    };
  } catch {
    return null;
  }
}

/**
 * Session state. STATED SHORTCUT (docs/THREAT_MODEL.md T21): the access token lives in memory and in
 * `sessionStorage` so a reload survives. It is not an HttpOnly cookie, so script injected into the page
 * could read it; mitigations are short expiry, no third-party scripts, and a per-tab (never
 * `localStorage`) lifetime. A production system would use a BFF with an HttpOnly session cookie.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private expiryTimer: ReturnType<typeof setTimeout> | undefined;
  private readonly _expired = signal(false);
  /** True after a session ended by itself (expiry or a rejected token), until the next sign-in. The login page says so. */
  readonly expired = this._expired.asReadonly();

  private readonly _session = signal<Session | null>(null);
  readonly session = this._session.asReadonly();
  readonly user = computed(() => this._session());
  readonly role = computed(() => this._session()?.role ?? null);
  readonly name = computed(() => this._session()?.name ?? '');
  readonly markets = computed(() => this._session()?.markets ?? []);
  readonly isAuthenticated = computed(() => this._session() !== null);

  constructor() {
    this.restore();
  }

  /** The token if the session is still within its expiry; clears it otherwise. */
  token(): string | null {
    const s = this._session();
    if (!s) return null;
    if (s.expiresAt <= Date.now()) {
      this.expire();
      return null;
    }
    return s.token;
  }

  /** True when signed in and unexpired. Guards call this, not the signal, so expiry is honoured. */
  isValid(): boolean {
    return this.token() !== null;
  }

  async login(email: string, password: string): Promise<Session> {
    const body: LoginRequest = { email, password };
    let res: LoginResponse;
    try {
      res = await firstValueFrom(this.http.post<LoginResponse>('/api/v1/auth/login', body));
    } catch (e) {
      throw ProblemError.from(e);
    }
    const decoded = res.accessToken ? decodeToken(res.accessToken) : null;
    if (!decoded) {
      throw new ProblemError({ status: 200, code: 'BAD_TOKEN', title: 'The server returned an unusable session' });
    }
    this.start(decoded);
    return decoded;
  }

  logout(): void {
    this._expired.set(false);
    this.clear();
  }

  /** The session ended without the user asking: drop it and remember why, so the login page can say so. */
  expire(): void {
    if (this._session() === null) return;
    this._expired.set(true);
    this.clear();
  }

  /** Drops the session everywhere (memory, sessionStorage, expiry timer). */
  clear(): void {
    if (this.expiryTimer !== undefined) clearTimeout(this.expiryTimer);
    this.expiryTimer = undefined;
    this._session.set(null);
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch {
      /* storage unavailable: memory-only session */
    }
  }

  private start(s: Session): void {
    this._expired.set(false);
    this._session.set(s);
    try {
      sessionStorage.setItem(SESSION_KEY, s.token);
    } catch {
      /* storage unavailable: session survives until reload only */
    }
    this.armExpiry(s);
  }

  private restore(): void {
    let token: string | null = null;
    try {
      token = sessionStorage.getItem(SESSION_KEY);
    } catch {
      return;
    }
    const s = token ? decodeToken(token) : null;
    if (s && s.expiresAt > Date.now()) {
      this._session.set(s);
      this.armExpiry(s);
    } else if (token) {
      this._expired.set(true);
      this.clear();
    }
  }

  /** Signs out on its own at expiry so a long-idle tab does not look signed in. */
  private armExpiry(s: Session): void {
    if (this.expiryTimer !== undefined) clearTimeout(this.expiryTimer);
    const ms = Math.min(s.expiresAt - Date.now(), 2 ** 31 - 1);
    this.expiryTimer = setTimeout(() => {
      this.expire();
      void this.router.navigate(['/login']);
    }, Math.max(ms, 0));
  }
}
