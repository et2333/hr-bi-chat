import { defineConfig, devices } from '@playwright/test'

/**
 * S8e Playwright E2E（问数/无权限/报表三主流程）。
 *
 * 前置：Java 后端 local profile 运行于 8080（`mvn -pl hrchat-bootstrap spring-boot:run`），
 * Vite 代理将 /api 转发至后端；本配置只负责拉起 Vite dev server。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5199',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --port 5199',
    port: 5199,
    reuseExistingServer: true,
    timeout: 120_000,
  },
})
