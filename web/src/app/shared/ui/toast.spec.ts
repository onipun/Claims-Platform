import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TOAST_MS, ToastService, UiToastHost } from './toast';

describe('toasts', () => {
  let fixture: ComponentFixture<UiToastHost>;
  let el: HTMLElement;
  let svc: ToastService;

  const tick = (ms: number) => {
    vi.advanceTimersByTime(ms);
    fixture.detectChanges();
  };

  beforeEach(() => {
    vi.useFakeTimers();
    fixture = TestBed.createComponent(UiToastHost);
    el = fixture.nativeElement;
    svc = TestBed.inject(ToastService);
    fixture.detectChanges();
  });
  afterEach(() => {
    fixture.destroy();
    vi.useRealTimers();
  });

  it('has both live regions in the DOM before any toast exists', () => {
    expect(el.querySelector('[role=status][aria-live=polite]')).not.toBeNull();
    expect(el.querySelector('[role=alert][aria-live=assertive]')).not.toBeNull();
  });

  it('puts a success toast in the polite region and an error in the assertive one', () => {
    svc.success('Saved');
    svc.error('Broke');
    fixture.detectChanges();
    expect(el.querySelector('[role=status]')!.textContent).toContain('Saved');
    expect(el.querySelector('[role=alert]')!.textContent).toContain('Broke');
  });

  it('dismisses a success toast after the timeout', () => {
    svc.success('Saved');
    fixture.detectChanges();
    tick(TOAST_MS + 10);
    expect(el.textContent).not.toContain('Saved');
  });

  it('pauses the timer while hovered and resumes on leave', () => {
    svc.success('Saved');
    fixture.detectChanges();
    const toast = el.querySelector<HTMLElement>('[data-kind=success]')!;
    tick(TOAST_MS - 1000);
    toast.dispatchEvent(new Event('mouseenter'));
    tick(60_000);
    expect(el.textContent).toContain('Saved');
    toast.dispatchEvent(new Event('mouseleave'));
    tick(TOAST_MS + 10);
    expect(el.textContent).not.toContain('Saved');
  });

  it('pauses while a control inside has focus', () => {
    svc.success('Saved');
    fixture.detectChanges();
    const toast = el.querySelector<HTMLElement>('[data-kind=success]')!;
    toast.dispatchEvent(new Event('focusin', { bubbles: true }));
    tick(60_000);
    expect(el.textContent).toContain('Saved');
    toast.dispatchEvent(new Event('focusout', { bubbles: true }));
    tick(TOAST_MS + 10);
    expect(el.textContent).not.toContain('Saved');
  });

  it('never auto-dismisses an error, only the Dismiss button does', () => {
    svc.error('Broke');
    fixture.detectChanges();
    tick(10 * 60_000);
    expect(el.textContent).toContain('Broke');
    el.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    fixture.detectChanges();
    expect(el.textContent).not.toContain('Broke');
  });
});
