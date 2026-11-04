import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { makeToken } from '../../../testing/token';
import { AuthService, SESSION_KEY, decodeToken } from '../../core/auth/auth.service';
import { UnreadUpdates, WINDOW, lastSeenKey } from './unread.service';

const UPDATES = '/api/v1/claimant/updates';
const iso = (ms: number) => new Date(ms).toISOString();
const T0 = Date.parse('2026-11-01T00:00:00Z');
const updates = (n: number, from = T0 + 1000) => ({ items: Array.from({ length: n }, (_, i) => ({ id: `u${i}`, at: iso(from + i * 1000) })) });

describe('UnreadUpdates', () => {
  let ctl: HttpTestingController;
  let svc: UnreadUpdates;
  let auth: AuthService;
  const signIn = (sub: string, role: 'CLAIMANT' | 'OFFICER' = 'CLAIMANT') =>
    (auth as unknown as { start(s: unknown): void }).start(decodeToken(makeToken({ role, sub })));

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    sessionStorage.setItem(SESSION_KEY, makeToken({ role: 'CLAIMANT', sub: 'user-a' }));
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    ctl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    svc = TestBed.inject(UnreadUpdates);
  });
  afterEach(() => {
    ctl.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  const load = async (body: object) => {
    const p = svc.refresh();
    ctl.expectOne((r) => r.url === UPDATES).flush(body);
    await p;
  };

  it('keeps "seen" per user: a second claimant in the same browser starts from their own value', async () => {
    await load(updates(3));
    expect(svc.count()).toBe(3);
    svc.markSeen(T0 + 3000);
    expect(localStorage.getItem(lastSeenKey('user-a'))).toBe(String(T0 + 3000));
    expect(svc.count()).toBe(0);

    signIn('user-b');
    expect(svc.seenAt()).toBe(0);
    expect(svc.count()).toBe(0); // user A's numbers are not B's
    await load(updates(2));
    expect(svc.count()).toBe(2);
    expect(localStorage.getItem(lastSeenKey('user-b'))).toBeNull();

    signIn('user-a');
    expect(svc.seenAt()).toBe(T0 + 3000);
  });

  it('forgets everything at logout and makes no request without a claimant session', async () => {
    await load(updates(3));
    expect(svc.badge()).toEqual({ text: '3', label: 'unread' });
    auth.logout();
    expect(svc.count()).toBe(0);
    expect(svc.badge()).toBeNull();
    await svc.refresh();
    ctl.expectNone((r) => r.url === UPDATES);
  });

  it('does not call the API for a staff session', async () => {
    signIn('officer-1', 'OFFICER');
    await svc.refresh();
    ctl.expectNone((r) => r.url === UPDATES);
    expect(svc.badge()).toBeNull();
  });

  it('shows "50+" at the cap', async () => {
    await load(updates(WINDOW));
    expect(svc.badge()).toEqual({ text: '50+', label: '50 or more unread' });
    await load(updates(49));
    expect(svc.badge()?.text).toBe('49');
  });

  it('shows a neutral state, not 0, when the count cannot be loaded; a later success clears it', async () => {
    const p = svc.refresh();
    ctl.expectOne((r) => r.url === UPDATES).flush({ code: 'X', title: 'down' }, { status: 503, statusText: 'x' });
    await p;
    expect(svc.count()).toBe(0);
    expect(svc.badge()).toEqual({ text: '?', label: 'unread count unavailable' });
    await load(updates(0));
    expect(svc.badge()).toBeNull();
  });

  it('a response that arrives after the user changed is not applied to the new user', async () => {
    const p = svc.refresh(); // user A
    const req = ctl.expectOne((r) => r.url === UPDATES);
    signIn('user-b');
    req.flush(updates(5));
    await p;
    expect(svc.count()).toBe(0);
    expect(svc.badge()).toBeNull();
  });

  it('marks seen up to the newest update shown, never up to now, and never backwards', async () => {
    await load(updates(3));
    svc.markSeen(T0 + 2000); // only the first two were shown
    expect(svc.count()).toBe(1);
    expect(localStorage.getItem(lastSeenKey('user-a'))).toBe(String(T0 + 2000));
    svc.markSeen(T0 + 500);
    expect(localStorage.getItem(lastSeenKey('user-a'))).toBe(String(T0 + 2000));
  });
});
