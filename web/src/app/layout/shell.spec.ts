import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { SESSION_KEY } from '../core/auth/auth.service';
import { makeToken } from '../../testing/token';
import { Shell } from './shell';

describe('Shell skip link', () => {
  it('moves focus to main and does not navigate', () => {
    sessionStorage.setItem(SESSION_KEY, makeToken({ role: 'OFFICER', markets: ['SG'], name: 'Olivia' }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        { provide: ActivatedRoute, useValue: { snapshot: { data: { nav: { area: 'Staff', items: [] } } } } },
      ],
    });
    const fixture = TestBed.createComponent(Shell);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const before = location.href;
    const link = fixture.nativeElement.querySelector('a[href="#main"]') as HTMLAnchorElement;
    const ev = new MouseEvent('click', { bubbles: true, cancelable: true });
    link.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('main#main'));
    expect(location.href).toBe(before);
    fixture.nativeElement.remove();
    sessionStorage.clear();
  });

  it('marks the background inert while the mobile drawer is open', () => {
    sessionStorage.setItem(SESSION_KEY, makeToken({ role: 'OFFICER', markets: ['SG'], name: 'Olivia' }));
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), { provide: ActivatedRoute, useValue: { snapshot: { data: { nav: { area: 'Staff', items: [] } } } } }],
    });
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('main')!.closest('[inert]')).toBeNull();
    (el.querySelector('button[aria-haspopup=dialog]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('main')!.closest('[inert]')).not.toBeNull();
    expect(el.querySelector('[role=dialog]')!.closest('[inert]')).toBeNull();
    sessionStorage.clear();
  });
});
