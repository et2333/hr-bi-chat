import { expect, test } from '@playwright/test'

test('clarification choices survive reload and analysis waits for confirmation', async ({ page }) => {
  test.skip(process.env.CONVERSATION_FIXTURE_E2E !== '1', 'Run via evals.conversation_smoke --browser')
  await page.addInitScript(() => {
    localStorage.setItem('hrchat_user_no', 'hr04')
    localStorage.setItem('hrchat_tenant_no', 't01')
  })
  let analysisStarts = 0
  page.on('request', request => {
    if (request.method() === 'POST' && /\/attribution$/.test(request.url())) analysisStarts++
  })
  await page.goto('/chat')
  await page.locator('.session-head button').click()
  const send = async (question: string) => {
    await page.locator('textarea[placeholder^="输入您的问题"]').fill(question)
    await page.locator('.input-actions button').click()
  }
  await send('人员变动情况')
  await expect(page.locator('.clarify-card')).toBeVisible()
  await page.getByRole('radio', { name: '离职人数' }).check()
  await page.getByRole('button', { name: '确认回答' }).click()
  await expect(page.getByRole('radio', { name: '上月', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '确认回答' })).toBeDisabled()
  await page.getByRole('radio', { name: '上月', exact: true }).check()
  await page.getByRole('button', { name: '确认回答' }).click()
  await expect(page.locator('.adopted-conditions').last()).toContainText('2026-08')
  await expect(page.locator('.interaction-history').last()).toContainText('你的选择：离职人数')
  await expect(page.locator('.interaction-history').last()).toContainText('你的选择：上月')
  await page.reload()
  await expect(page.locator('.interaction-history').last()).toContainText('你的选择：上月')
  await send('分析变化')
  await expect(page.getByTestId('current-period').last()).toContainText('2026-08-01')
  await expect(page.getByTestId('confirm-analysis').last()).toBeVisible()
  expect(analysisStarts).toBe(0)
})
