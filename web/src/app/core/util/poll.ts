import { DestroyRef, inject } from '@angular/core';

export interface PollHandle {
  stop(): void;
  /** Run the task now (also resets the timer). */
  refresh(): void;
}

/**
 * Calls `task` every `intervalMs` while the tab is visible, and stops for good when the owning
 * injection context is destroyed. Hidden tab: the timer pauses; on becoming visible again the task
 * runs immediately, so an "as of" view is never left stale after the user returns. Runs are serial:
 * the next tick is scheduled only after the previous run settles, and a failed run does not stop polling.
 * Must be called in an injection context (component or service constructor/field initialiser).
 */
export function poll(task: () => Promise<unknown> | unknown, intervalMs: number): PollHandle {
  const destroyRef = inject(DestroyRef);
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;
  let running = false;

  const clear = () => {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  };
  const schedule = () => {
    clear();
    if (stopped || document.visibilityState === 'hidden') return;
    timer = setTimeout(run, intervalMs);
  };
  const run = async () => {
    clear();
    if (stopped || running) return;
    running = true;
    try {
      await task();
    } catch {
      // The task owns its error reporting; polling continues.
    } finally {
      running = false;
      schedule();
    }
  };
  const onVisibility = () => {
    if (document.visibilityState === 'visible') void run();
    else clear();
  };
  const stop = () => {
    stopped = true;
    clear();
    document.removeEventListener('visibilitychange', onVisibility);
  };

  document.addEventListener('visibilitychange', onVisibility);
  destroyRef.onDestroy(stop);
  schedule();
  return { stop, refresh: () => void run() };
}
