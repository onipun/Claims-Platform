import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ProblemError } from '../api/problem-error';
import { loadable } from './loadable';
import { PollHandle, poll } from './poll';

describe('loadable', () => {
  it('goes loading -> ready, empty and error', async () => {
    const ok = loadable(async () => [1]);
    expect(ok.state().status).toBe('idle');
    const p = ok.load();
    expect(ok.loading()).toBe(true);
    await p;
    expect(ok.state().status).toBe('ready');
    expect(ok.data()).toEqual([1]);

    const none = loadable(async () => [] as number[]);
    await none.load();
    expect(none.empty()).toBe(true);

    const bad = loadable<number>(async () => {
      throw new ProblemError({ status: 500, code: 'X', title: 'boom' });
    });
    await bad.load();
    expect(bad.error()?.code).toBe('X');
  });

  it('ignores a superseded load, even when the older one resolves last', async () => {
    const pending: { resolve(v: string): void; reject(e: unknown): void }[] = [];
    const l = loadable(() => new Promise<string>((resolve, reject) => pending.push({ resolve, reject })));
    const first = l.load();
    const second = l.load();
    // Newer first, then the older one: the older must not overwrite.
    pending[1].resolve('new');
    await second;
    expect(l.data()).toBe('new');
    pending[0].resolve('old');
    await first;
    expect(l.data()).toBe('new');
    expect(l.state().status).toBe('ready');
  });

  it('ignores a superseded failure and a superseded success while loading', async () => {
    const pending: { resolve(v: string): void; reject(e: unknown): void }[] = [];
    const l = loadable(() => new Promise<string>((resolve, reject) => pending.push({ resolve, reject })));
    const first = l.load();
    const second = l.load();
    pending[0].reject(new ProblemError({ status: 500, code: 'OLD', title: 'old failed' }));
    await first;
    expect(l.state().status).toBe('loading');
    pending[1].resolve('ok');
    await second;
    expect(l.data()).toBe('ok');
    expect(l.error()).toBeNull();
  });
});

describe('poll', () => {
  @Component({ template: '' })
  class Host {
    calls = 0;
    handle: PollHandle = poll(() => void this.calls++, 1000);
  }
  const setVisibility = (v: DocumentVisibilityState) => {
    Object.defineProperty(document, 'visibilityState', { value: v, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  };
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    setVisibility('visible');
  });

  it('ticks while visible, pauses when hidden, resumes with an immediate run, stops on destroy', async () => {
    const fixture = TestBed.createComponent(Host);
    const host = fixture.componentInstance;
    await vi.advanceTimersByTimeAsync(3100);
    expect(host.calls).toBe(3);

    setVisibility('hidden');
    await vi.advanceTimersByTimeAsync(5000);
    expect(host.calls).toBe(3);

    setVisibility('visible');
    await vi.advanceTimersByTimeAsync(0);
    expect(host.calls).toBe(4);

    fixture.destroy();
    await vi.advanceTimersByTimeAsync(5000);
    expect(host.calls).toBe(4);
  });
});
