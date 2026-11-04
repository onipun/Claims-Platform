import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { makeToken } from '../../../testing/token';
import { LoginPage } from './login.page';

@Component({ template: '' })
class Blank {}

describe('LoginPage', () => {
  let fixture: ComponentFixture<LoginPage>;
  let el: HTMLElement;
  let ctl: HttpTestingController;
  let router: Router;

  const input = (type: string) => el.querySelector<HTMLInputElement>(`input[type=${type}]`)!;
  const type = (i: HTMLInputElement, v: string) => {
    i.value = v;
    i.dispatchEvent(new Event('input'));
    i.dispatchEvent(new Event('blur'));
  };
  const settle = async () => {
    await new Promise((r) => setTimeout(r));
    fixture.detectChanges();
  };
  const submit = async () => {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'claimant', component: Blank },
          { path: 'staff', component: Blank },
          { path: 'login', component: LoginPage },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    fixture = TestBed.createComponent(LoginPage);
    el = fixture.nativeElement;
    ctl = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });
  afterEach(() => sessionStorage.clear());

  it('binds labels to inputs', () => {
    for (const [label, t] of [['Email', 'email'], ['Password', 'password']] as const) {
      const l = [...el.querySelectorAll('label')].find((x) => x.textContent?.includes(label))!;
      expect(l.getAttribute('for')).toBe(input(t).id);
    }
  });

  it('empty submit shows both required errors and sends no request', async () => {
    await submit();
    expect(el.textContent).toContain('Enter your email address.');
    expect(el.textContent).toContain('Enter your password.');
    expect(input('email').getAttribute('aria-invalid')).toBe('true');
    expect(input('email').getAttribute('aria-describedby')).toBeTruthy();
    ctl.expectNone('/api/v1/auth/login');
  });

  it('rejects a malformed email', async () => {
    type(input('email'), 'not-an-email');
    type(input('password'), 'x');
    await submit();
    expect(el.textContent).toContain('Enter a valid email address.');
    ctl.expectNone('/api/v1/auth/login');
  });

  it('shows the dev demo hint in a dev build', () => {
    expect(el.querySelector('[data-testid=demo-hint]')).not.toBeNull();
  });

  it('shows an error banner on 401 and clears the password', async () => {
    type(input('email'), 'a@demo.test');
    type(input('password'), 'wrong');
    await submit();
    ctl.expectOne('/api/v1/auth/login').flush(
      { code: 'INVALID_CREDENTIALS', title: 'Unauthorized', status: 401 },
      { status: 401, statusText: 'Unauthorized' },
    );
    await settle();
    expect(el.querySelector('[data-testid=login-error]')?.textContent).toContain('Invalid email or password.');
    expect(input('password').value).toBe('');
  });

  it('disables the button while submitting, then routes the role to its home', async () => {
    const nav = vi.spyOn(router, 'navigateByUrl');
    type(input('email'), 'o@demo.test');
    type(input('password'), 'Password1!');
    await submit();
    const btn = el.querySelector<HTMLButtonElement>('button[type=submit]')!;
    expect(btn.disabled).toBe(true);
    ctl.expectOne('/api/v1/auth/login').flush({ accessToken: makeToken({ role: 'OFFICER' }), expiresIn: 900, tokenType: 'Bearer' });
    await settle();
    expect(nav).toHaveBeenCalledWith('/staff');
  });
});
