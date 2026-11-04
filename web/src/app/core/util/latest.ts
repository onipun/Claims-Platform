/**
 * "Only the latest request may write state." Call `begin()` when a request starts; the returned function
 * says whether that request is still the newest once its response arrives. `invalidate()` makes every
 * request already in flight stale (use it when the screen changes subject, or a command succeeds).
 */
export interface RequestGuard {
  begin(): () => boolean;
  invalidate(): void;
}

export function requestGuard(): RequestGuard {
  let seq = 0;
  return {
    begin() {
      const mine = ++seq;
      return () => mine === seq;
    },
    invalidate() {
      seq++;
    },
  };
}
