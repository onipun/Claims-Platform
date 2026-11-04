import { expect, test, type Page } from '@playwright/test';

/**
 * The gateway sends a Content-Security-Policy that forbids inline script and third-party origins. The app must
 * still render and work under it: any CSP violation is logged by the browser as a console error, and fails this.
 * Independent of the claim journey, so it keeps guarding the policy whatever the journey does.
 */
const PASSWORD = 'Password1!';

function watch(page: Page): string[] {
  const problems: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error' && /content security policy|refused to (load|apply|execute|connect)|violates the following/i.test(m.text())) {
      problems.push(m.text());
    }
  });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  return problems;
}

async function signIn(page: Page, user: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email').fill(`${user}@demo.test`);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

test('static files and both API proxies carry one copy of each security header', async ({ request }) => {
  const token = (await (await request.post('/api/v1/auth/login', { data: { email: 'officer.sg@demo.test', password: PASSWORD } })).json()).accessToken;
  const auth = { Authorization: `Bearer ${token}` };
  for (const [name, res] of [
    ['static /', await request.get('/')],
    ['static asset route (SPA fallback)', await request.get('/staff/claims')],
    ['claims API', await request.get('/api/v1/staff/claims', { headers: auth })],
    ['insights API', await request.get('/api/v1/insights/exposure', { headers: auth })],
    ['API error', await request.get('/api/v1/staff/claims/not-a-uuid', { headers: auth })],
  ] as const) {
    const h = res.headersArray();
    const count = (n: string) => h.filter((x) => x.name.toLowerCase() === n).length;
    expect(count('content-security-policy'), `${name}: CSP`).toBe(1);
    expect(count('x-content-type-options'), `${name}: nosniff`).toBe(1);
    expect(count('referrer-policy'), `${name}: referrer`).toBe(1);
    expect(res.headers()['x-content-type-options']).toBe('nosniff');
    expect(res.headers()['referrer-policy']).toBe('no-referrer');
    expect(res.headers()['content-security-policy']).toContain("default-src 'self'");
    expect(res.headers()['content-security-policy']).toContain("frame-ancestors 'none'");
  }
});

test('the app renders and works for each role under the policy, with no violation reported', async ({ page }) => {
  const problems = watch(page);
  await signIn(page, 'claimant.sg');
  await page.goto('/claimant/claims');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await page.goto('/claimant/new');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  // styles apply (the stylesheet is not stuck at media=print, which an inline-handler loader would do under this policy)
  const bg = await page.evaluate(() => getComputedStyle(document.body).fontFamily);
  expect(bg.toLowerCase()).toContain('inter');
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await signIn(page, 'officer.sg');
  await page.goto('/staff/claims');
  await expect(page.getByRole('heading', { name: 'Claims', level: 1 })).toBeVisible();
  await page.goto('/staff/insights');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  expect(problems, 'CSP violations or page errors').toEqual([]);
});
