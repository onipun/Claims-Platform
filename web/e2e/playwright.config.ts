import { defineConfig } from '@playwright/test';

// Real backend, real login, no mocks. BASE_URL is the nginx gateway (http://web inside the compose network).
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  timeout: 240_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  outputDir: '/tmp/e2e-results',
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:14200',
    actionTimeout: 15_000,
    trace: 'off',
    screenshot: 'only-on-failure',
    viewport: { width: 1280, height: 900 },
  },
});
