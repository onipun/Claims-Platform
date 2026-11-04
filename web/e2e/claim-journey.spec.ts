import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

/**
 * One journey, three roles, real backend and real login, no mocks and no fixed sleeps.
 * claimant reports a motor claim and uploads a document -> officer picks it up and asks a question ->
 * claimant answers -> officer assesses, and is refused an approval above their limit (SGD 10,000) ->
 * manager approves and settles -> the dashboard catches up (bounded poll, it lags) -> claimant sees the decision.
 * axe (WCAG 2.2 AA) runs on each main screen and any violation fails the test.
 */

const PASSWORD = 'Password1!';
const ESTIMATE = '15000.00'; // above the officer's SGD 10,000 limit, so the approval must go to the manager
const RESERVE = '14000.00';
const APPROVED = '12000.00';
const QUESTION = 'Which workshop is repairing the vehicle?';
const ANSWER = 'Ah Seng Motors, Ubi Avenue 1.';
// 1x1 transparent PNG
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

async function axe(page: Page, screen: string): Promise<void> {
  // The nav links use transition-colors. axe reads the colour mid-transition after a route change and reports a
  // contrast failure that is not on screen once settled. Wait for finite animations to end (not a timed sleep).
  await page.waitForFunction(() =>
    document.getAnimations().every((a) => a.playState !== 'running' || !Number.isFinite(a.effect?.getComputedTiming().endTime as number)),
  );
  const res = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const summary = res.violations.map((v) => `${v.id} (${v.impact}): ${v.help} - ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
  expect(summary, `axe violations on ${screen}`).toEqual([]);
}

async function signIn(page: Page, user: string): Promise<void> {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await page.getByLabel('Email').fill(`${user}@demo.test`);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
}

/** Yesterday's date in Singapore, which the claim form reads as the market's local time. */
function yesterdayInSingapore(): string {
  const d = new Date(Date.now() - 24 * 3600 * 1000);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Singapore' }).format(d);
}

const digits = (s: string): number => Number(s.replace(/[^0-9.]/g, '')) || 0;

test('a motor claim goes from report to settlement and shows on the dashboard', async ({ page }) => {
  // ---- Claimant reports the claim ----
  await signIn(page, 'claimant.sg');
  await page.goto('/claimant/new');
  await page.getByRole('radio', { name: /MOT-SG-100001/ }).check();
  await page.getByLabel('Type of claim').selectOption({ label: 'Damage to my own vehicle' });
  await page.getByLabel('Date of incident').fill(yesterdayInSingapore());
  await page.getByLabel('Time of incident').fill('10:30');
  await page.getByLabel('Description').fill('Another car reversed into my front bumper in a car park.');
  await page.getByLabel(/Estimated loss/).fill(ESTIMATE);
  await page.getByLabel('Vehicle registration').fill('SJB1234X');
  await page.getByLabel('Driver at the time').fill('Alicia Tan');
  // R12: motor claims require the accident location. The label is the contract with the claim form.
  await page.getByLabel('Accident location').fill('Junction of Orchard Road and Bras Basah Road');
  await axe(page, 'Report a claim');
  await page.getByRole('button', { name: 'Submit claim' }).click();

  await expect(page.getByRole('heading', { name: 'We have received your claim' })).toBeVisible();
  const claimNumber = (await page.getByTestId('claim-number').innerText()).trim();
  expect(claimNumber).toMatch(/^CLM-SG-/);
  await axe(page, 'Claim received');

  // ---- Claimant uploads a document ----
  await page.getByRole('link', { name: 'Upload documents' }).click();
  await expect(page.getByRole('heading', { name: claimNumber })).toBeVisible();
  await page.getByLabel(/Choose a file for/).first().setInputFiles({ name: 'registration-card.png', mimeType: 'image/png', buffer: PNG });
  await expect(page.getByTestId('upload-notice')).toHaveText('Uploaded');
  await expect(page.getByRole('button', { name: /Download/ }).first()).toBeVisible();
  await axe(page, 'Claim (claimant), submitted');
  await signOut(page);

  // ---- Officer picks it up and asks a question ----
  await signIn(page, 'officer.sg');
  await page.goto('/staff/claims');
  await expect(page.getByRole('heading', { name: 'Claims', level: 1 })).toBeVisible();
  await axe(page, 'Staff queue');
  await page.getByRole('button', { name: `Pick up ${claimNumber}` }).click();
  await page.getByRole('tab', { name: /Mine|My claims/i }).click();
  await page.getByRole('link', { name: claimNumber }).click();
  await expect(page.getByRole('heading', { name: claimNumber })).toBeVisible();
  await page.getByRole('button', { name: 'Request info' }).click();
  await page.getByLabel(/Question for the claimant/).fill(QUESTION);
  await axe(page, 'Staff workspace, request info');
  await page.getByRole('button', { name: 'Send request to claimant' }).click();
  await expect(page.getByText('Awaiting info').first()).toBeVisible();
  await signOut(page);

  // ---- Claimant answers ----
  await signIn(page, 'claimant.sg');
  await page.goto('/claimant/claims');
  await page.getByRole('link', { name: new RegExp(claimNumber) }).first().click();
  await expect(page.getByTestId('question-text')).toHaveText(QUESTION);
  await page.getByLabel(/Your answer/).fill(ANSWER);
  await page.getByRole('button', { name: 'Send answer' }).click();
  await expect(page.getByText('Your answer was sent')).toBeVisible();
  await signOut(page);

  // ---- Officer assesses; approval above their limit is refused ----
  await signIn(page, 'officer.sg');
  await page.goto('/staff/claims');
  await page.getByRole('tab', { name: /Mine|My claims/i }).click();
  await page.getByRole('link', { name: claimNumber }).click();
  await page.getByRole('button', { name: 'Assess' }).click();
  await page.getByLabel(/Case reserve/).fill(RESERVE);
  await page.getByRole('button', { name: 'Record assessment' }).click();
  await expect(page.getByText('Assessed', { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Approve', exact: true }).first().click();
  await page.getByLabel(/Approved amount/).fill(APPROVED);
  await page.getByRole('button', { name: 'Approve', exact: true }).last().click();
  await expect(page.getByTestId('notice-authority')).toContainText('above your approval authority');
  await axe(page, 'Staff workspace, authority exceeded');
  await signOut(page);

  // ---- Manager reads the dashboard baseline, approves and settles ----
  await signIn(page, 'manager.sg');
  await page.goto('/staff/insights');
  const exposure = page.getByRole('table', { name: 'Case exposure by market and line' });
  const sgMotor = exposure.getByRole('row', { name: /^SG\s+MOTOR/i });
  const paidBefore = digits(await sgMotor.getByRole('cell').nth(6).innerText());
  await axe(page, 'Insights');

  await page.goto('/staff/claims');
  await page.getByRole('tab', { name: /All \(team\)/ }).click();
  await page.getByRole('combobox', { name: 'Status' }).selectOption({ label: 'Assessed' });
  await page.getByRole('link', { name: claimNumber }).click();
  await page.getByRole('button', { name: 'Approve', exact: true }).first().click();
  await page.getByLabel(/Approved amount/).fill(APPROVED);
  await page.getByRole('button', { name: 'Approve', exact: true }).last().click();
  await expect(page.getByText('Approved', { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Settle', exact: true }).first().click();
  await page.getByLabel(/Payment amount/).fill(APPROVED);
  await page.getByRole('button', { name: 'Settle…' }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Settle claim' }).click();
  await expect(page.getByText('Settled', { exact: true }).first()).toBeVisible();
  await axe(page, 'Staff workspace, settled');

  // ---- The dashboard lags the claim: poll for it, bounded ----
  await page.goto('/staff/insights');
  await expect
    .poll(async () => digits(await exposure.getByRole('row', { name: /^SG\s+MOTOR/i }).getByRole('cell').nth(6).innerText()), {
      message: 'dashboard Paid for SG Motor rises by the settled amount',
      timeout: 60_000,
      intervals: [1_000, 2_000, 5_000],
    })
    .toBeGreaterThanOrEqual(paidBefore + digits(APPROVED));
  await signOut(page);

  // ---- Claimant sees the decision ----
  await signIn(page, 'claimant.sg');
  await page.goto('/claimant/claims');
  await page.getByRole('link', { name: new RegExp(claimNumber) }).first().click();
  await expect(page.getByRole('heading', { name: 'Decision: approved' })).toBeVisible();
  expect(digits(await page.getByTestId('decision-amount').innerText())).toBe(digits(APPROVED));
  await axe(page, 'Claim (claimant), decision');
});
