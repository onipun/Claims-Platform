import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Announcer } from '../shared/ui/announcer';
import { RouteFocus } from './route-focus';

@Component({ selector: 'stub-page', template: '' })
class Stub {}

describe('RouteFocus', () => {
  let router: Router;
  let announce: ReturnType<typeof vi.fn>;
  let main: HTMLElement;

  const later = () => new Promise((r) => setTimeout(r, 5));

  beforeEach(async () => {
    main = document.createElement('main');
    main.id = 'main';
    main.tabIndex = -1;
    main.innerHTML = '<h1>Claims</h1>';
    document.body.appendChild(main);
    announce = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'a', component: Stub },
          { path: 'b', component: Stub },
        ]),
        { provide: Announcer, useValue: { polite: announce, assertive: announce } },
      ],
    });
    TestBed.inject(RouteFocus);
    router = TestBed.inject(Router);
    await router.navigateByUrl('/a');
    await later();
  });
  afterEach(() => main.remove());

  it('does nothing on the first load', () => {
    expect(announce).not.toHaveBeenCalled();
    expect(document.activeElement).not.toBe(main.querySelector('h1'));
  });

  it('focuses the heading and announces it on a real navigation', async () => {
    await router.navigateByUrl('/b');
    await later();
    expect(document.activeElement).toBe(main.querySelector('h1'));
    // Focus on the h1 already makes the screen reader say it: no second announcement.
    expect(announce).not.toHaveBeenCalled();
  });

  it('falls back to main when there is no h1, and announces then', async () => {
    const rf = TestBed.inject(RouteFocus);
    rf.retryMs = 1;
    rf.retries = 3;
    main.innerHTML = '<p>no heading</p>';
    document.title = 'Reports';
    await router.navigateByUrl('/b');
    await vi.waitFor(() => expect(document.activeElement).toBe(main));
    expect(announce).toHaveBeenCalledTimes(1);
    expect(announce.mock.calls[0][0]).toContain('Reports');
  });

  it('waits for a heading that a data page renders after its first read', async () => {
    main.innerHTML = '<p>loading</p>';
    await router.navigateByUrl('/b');
    await new Promise((r) => setTimeout(r, 30));
    expect(document.activeElement).not.toBe(main); // still waiting, not parked on main
    main.innerHTML = '<h1>CLM-1</h1>';
    await vi.waitFor(() => expect(document.activeElement).toBe(main.querySelector('h1')));
    expect(announce).not.toHaveBeenCalled();
  });

  it('does not pull focus out of a field the user reached while it waited for the heading', async () => {
    main.innerHTML = '<input id="f">';
    await router.navigateByUrl('/b');
    await new Promise((r) => setTimeout(r, 30));
    main.querySelector<HTMLInputElement>('#f')!.focus();
    main.insertAdjacentHTML('afterbegin', '<h1>CLM-1</h1>');
    await new Promise((r) => setTimeout(r, 250));
    expect(document.activeElement).toBe(main.querySelector('#f'));
  });

  describe('with the followed link still in the DOM and focused', () => {
    let link: HTMLAnchorElement;
    beforeEach(() => {
      link = document.createElement('a');
      link.href = '#nav';
      link.textContent = 'Claims';
      document.body.appendChild(link);
      link.focus();
      expect(document.activeElement).toBe(link);
    });
    afterEach(() => link.remove());

    it('still moves focus to a heading that renders late', async () => {
      main.innerHTML = '<p>loading</p>';
      await router.navigateByUrl('/b');
      await new Promise((r) => setTimeout(r, 30));
      main.innerHTML = '<h1>CLM-1</h1>';
      await vi.waitFor(() => expect(document.activeElement).toBe(main.querySelector('h1')));
      expect(document.activeElement).not.toBe(link);
    });

    it('falls back to main at the timeout and announces', async () => {
      const rf = TestBed.inject(RouteFocus);
      rf.retryMs = 1;
      rf.retries = 3;
      main.innerHTML = '<p>no heading</p>';
      document.title = 'Reports';
      await router.navigateByUrl('/b');
      await vi.waitFor(() => expect(document.activeElement).toBe(main));
      expect(announce).toHaveBeenCalledTimes(1);
      expect(announce.mock.calls[0][0]).toBe('Navigated to Reports');
    });

    it('leaves focus on a control the user moved to, and announces the navigation', async () => {
      main.innerHTML = '<button id="menu">Account</button>';
      document.title = 'CLM-1';
      await router.navigateByUrl('/b');
      await new Promise((r) => setTimeout(r, 30));
      main.querySelector<HTMLButtonElement>('#menu')!.focus();
      main.insertAdjacentHTML('afterbegin', '<h1>CLM-1</h1>');
      await vi.waitFor(() => expect(announce).toHaveBeenCalledWith('Navigated to CLM-1'));
      expect(document.activeElement).toBe(main.querySelector('#menu'));
      expect(announce).toHaveBeenCalledTimes(1);
    });
  });

  it('a second navigation cancels the first one\'s pending timer', async () => {
    const rf = TestBed.inject(RouteFocus);
    rf.retryMs = 20;
    rf.retries = 1000;
    main.innerHTML = '<p>loading</p>';
    await router.navigateByUrl('/b');
    await new Promise((r) => setTimeout(r, 70)); // the first chain is several attempts in
    const spy = vi.spyOn(rf, 'focusPage');
    await router.navigateByUrl('/a');
    await new Promise((r) => setTimeout(r, 110));
    const attempts = spy.mock.calls.map((c) => c[0] ?? 0);
    expect(attempts.length).toBeGreaterThan(2);
    // Only the second navigation's chain runs: it restarts at 0 and counts up one by one.
    expect(attempts).toEqual(attempts.map((_, i) => i));
    rf['cancel']();
  });

  it('is silent on a query-only or fragment-only change', async () => {
    await router.navigateByUrl('/a?tab=workload');
    await later();
    await router.navigateByUrl('/a?tab=performance#x');
    await later();
    expect(announce).not.toHaveBeenCalled();
    expect(document.activeElement).not.toBe(main.querySelector('h1'));
  });
});
