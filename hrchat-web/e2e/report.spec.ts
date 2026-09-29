import { expect, test } from '@playwright/test'

/**
 * 报表主流程：进入报表中心 → 新建报表 → 编辑页保存（模板直查）→ 列表可见。
 */
test('报表：新建并保存自定义报表', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('hrchat_user_no', 'hr01'))
  await page.goto('/reports')

  await expect(page.locator('.list-toolbar')).toBeVisible()
  await page.getByRole('button', { name: '新建报表' }).click()
  await page.waitForURL(/\/reports\/editor/)

  // 编辑页保存（报表名称必填 + 模板必选）
  const nameInput = page.locator('input[placeholder*="人力月报"]').first()
  await nameInput.fill(`E2E 报表 ${Date.now()}`)
  // 选择模板下拉第一项
  await page.locator('.ant-select').first().click()
  await page.locator('.ant-select-dropdown .ant-select-item').first().click()
  await page.getByRole('button', { name: '创建报表' }).click()

  // 回到报表详情（保存成功跳转 /reports/:id）
  await page.waitForURL(/\/reports\/\d+/, { timeout: 15_000 })
  await expect(page.locator('.detail-page .report-title').first()).toBeVisible()
})
