import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';

// Use installed Chrome by default; set LAB_BROWSER_CHANNEL=msedge/chromium as needed.
export default defineConfig({
  testDir: '.',
  testMatch: 'mobile.spec.mjs',
  timeout: 90_000,
  expect: { timeout: 8_000 },
  workers: 2,
  outputDir: fileURLToPath(new URL('../test-results', import.meta.url)),
  reporter: [['list'], ['json', { outputFile: fileURLToPath(new URL('../artifacts/mobile/results.json', import.meta.url)) }]],
  use: {
    baseURL: 'http://127.0.0.1:5186',
    channel: process.env.LAB_BROWSER_CHANNEL || 'chrome',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'off',
    screenshot: 'off',
    video: 'off'
  },
  projects: [
    { name: 'phone-320', use: { viewport: { width: 320, height: 740 }, isMobile: true, hasTouch: true } },
    { name: 'phone-390', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
    { name: 'tablet-768', use: { viewport: { width: 768, height: 1024 }, hasTouch: true } },
    { name: 'landscape-900', use: { viewport: { width: 900, height: 440 }, isMobile: true, hasTouch: true } },
    { name: 'desktop-1440', use: { viewport: { width: 1440, height: 900 } } }
  ],
  webServer: {
    command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 5186 --strictPort',
    url: 'http://127.0.0.1:5186',
    reuseExistingServer: false,
    timeout: 60_000
  }
});
