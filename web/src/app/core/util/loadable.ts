import { Signal, computed, signal } from '@angular/core';
import { ProblemError } from '../api/problem-error';

export type LoadState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; error: ProblemError }
  | { status: 'empty' }
  | { status: 'ready'; data: T };

export interface Loadable<T> {
  readonly state: Signal<LoadState<T>>;
  readonly data: Signal<T | null>;
  readonly loading: Signal<boolean>;
  readonly error: Signal<ProblemError | null>;
  readonly empty: Signal<boolean>;
  /** Run the loader. Overlapping calls: only the latest one may write state. */
  load(): Promise<void>;
}

const isEmpty = (v: unknown): boolean =>
  v == null || (Array.isArray(v) && v.length === 0) ||
  (typeof v === 'object' && 'content' in (v as object) && Array.isArray((v as { content: unknown }).content) &&
    (v as { content: unknown[] }).content.length === 0);

/**
 * The loading / error / empty / ready pattern every async screen uses, as one signal.
 * Template: `@switch (list.state().status)`. A reload keeps showing the old data
 * (status stays 'ready') only if the caller wants stale-while-revalidate; here a reload goes to
 * 'loading' so skeletons show, which is the simpler and more honest default.
 */
export function loadable<T>(loader: () => Promise<T>, opts?: { isEmpty?: (v: T) => boolean }): Loadable<T> {
  const state = signal<LoadState<T>>({ status: 'idle' });
  let seq = 0;
  const empty = opts?.isEmpty ?? isEmpty;
  return {
    state: state.asReadonly(),
    data: computed(() => {
      const s = state();
      return s.status === 'ready' ? s.data : null;
    }),
    loading: computed(() => state().status === 'loading'),
    error: computed(() => {
      const s = state();
      return s.status === 'error' ? s.error : null;
    }),
    empty: computed(() => state().status === 'empty'),
    async load() {
      const mine = ++seq;
      state.set({ status: 'loading' });
      try {
        const data = await loader();
        if (mine !== seq) return;
        state.set(empty(data) ? { status: 'empty' } : { status: 'ready', data });
      } catch (e) {
        if (mine !== seq) return;
        state.set({ status: 'error', error: ProblemError.from(e) });
      }
    },
  };
}
