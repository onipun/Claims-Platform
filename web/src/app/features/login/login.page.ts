import { Component, inject, isDevMode, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { LucideAngularModule, Loader2, Shield } from 'lucide-angular';
import { ProblemError } from '../../core/api/problem-error';
import { AuthService } from '../../core/auth/auth.service';
import { safeReturnUrl } from '../../core/auth/guards';
import { homeFor } from '../../core/auth/roles';
import { UiButton } from '../../shared/ui/button';
import { UI_CARD } from '../../shared/ui/card';
import { UiInput } from '../../shared/ui/controls';
import { UiFormField } from '../../shared/ui/form-field';
import { PRODUCT_NAME } from '../../layout/shell';

/** Demo accounts from the `demo` backend profile. Shown in dev builds only (isDevMode() is false in production). */
const DEMO_USERS = [
  { email: 'claimant.sg@demo.test', label: 'Claimant (SG)' },
  { email: 'officer.sg@demo.test', label: 'Officer (SG)' },
  { email: 'manager.sg@demo.test', label: 'Manager (SG)' },
] as const;
const DEMO_PASSWORD = 'Password1!';

@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule, LucideAngularModule, UI_CARD, UiButton, UiInput, UiFormField],
  templateUrl: './login.page.html',
})
export class LoginPage {
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly product = PRODUCT_NAME;
  protected readonly icons = { Loader2, Shield };
  protected readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    password: ['', [Validators.required, Validators.maxLength(200)]],
  });
  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly problem = signal<ProblemError | null>(null);
  protected readonly demoUsers = isDevMode() ? DEMO_USERS : [];

  /** Error text for a field once it has been touched or the form was submitted. */
  protected fieldError(name: 'email' | 'password'): string | null {
    const c = this.form.controls[name];
    if (!(c.touched || this.submitted()) || c.valid) return null;
    const server = this.problem()?.fieldMessage(name);
    if (c.errors?.['server'] && server) return server;
    if (c.errors?.['required']) return name === 'email' ? 'Enter your email address.' : 'Enter your password.';
    if (c.errors?.['email']) return 'Enter a valid email address.';
    if (c.errors?.['maxlength']) return 'This is too long.';
    return 'Invalid value.';
  }

  protected useDemo(email: string): void {
    this.form.setValue({ email, password: DEMO_PASSWORD });
    this.problem.set(null);
  }

  protected async submit(): Promise<void> {
    this.submitted.set(true);
    this.problem.set(null);
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const { email, password } = this.form.getRawValue();
    try {
      const session = await this.auth.login(email.trim(), password);
      const target = safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')) ?? homeFor(session.role);
      await this.router.navigateByUrl(target);
    } catch (e) {
      const p = ProblemError.from(e);
      this.problem.set(p);
      for (const [field, msg] of Object.entries(p.fieldMessages)) {
        const c = field === 'email' || field === 'password' ? this.form.controls[field] : null;
        if (c && msg) c.setErrors({ server: true });
      }
      if (p.code === 'INVALID_CREDENTIALS' || p.status === 401) this.form.controls.password.reset('');
    } finally {
      this.submitting.set(false);
    }
  }

  protected bannerText(): string {
    const p = this.problem();
    if (!p) return '';
    if (p.status === 401 || p.code === 'INVALID_CREDENTIALS') return 'Invalid email or password.';
    if (p.code === 'NETWORK') return 'Cannot reach the server. Check your connection and try again.';
    if (p.status === 429) return 'Too many attempts. Wait a moment and try again.';
    if (p.status >= 500) return 'The service is unavailable right now. Try again shortly.';
    return p.detail ?? p.title;
  }
}
