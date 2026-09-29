import { expect, test } from '@playwright/test'

/**
 * 问数主流程：输入自然语言 → SSE 流式答案 → 结论卡 + 数据明细（S4 主链路端到端）。
 */
test('问数主流程：研发中心在职人数', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('hrchat_user_no', 'hr01'))
  await page.goto('/chat')

  // 新建会话，避免历史轮次干扰断言
  await page.locator('.session-head button').click()

  await page.locator('textarea[placeholder^="输入您的问题"]').fill('研发中心在职人数')
  await page.locator('.input-actions button').click()

  // 流式结束标志：答案完成（ANSWER_DONE → 结论卡片）
  await expect(page.locator('.answer-card').first()).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('.answer-card').first()).toContainText('在职人数')
  // 结论卡含数值
  await expect(page.locator('.metric-card').first()).toBeVisible()
})
