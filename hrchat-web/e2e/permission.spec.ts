import { expect, test } from '@playwright/test'

/**
 * 数据越权：hr02（仅销售部）询问研发中心 → 流内 ERROR(HRC-2003) → 无权限占位态。
 */
test('无权限：越权组织询问被拦截', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('hrchat_user_no', 'hr02'))
  await page.goto('/chat')

  // 新建会话，避免历史轮次干扰断言
  await page.locator('.session-head button').click()

  await page.locator('textarea[placeholder^="输入您的问题"]').fill('研发中心在职人数')
  await page.locator('.input-actions button').click()

  await expect(page.locator('.state-empty').first()).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('.state-empty').first()).toContainText('无访问权限')
})
