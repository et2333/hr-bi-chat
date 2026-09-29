import { expect, test } from '@playwright/test'

/**
 * P3 报表详情页交互：图表类型一键切换 / AI 洞察解读 / 明细表数据。
 * 通过 API 实例化模板生成报表（经 Vite 代理转发到后端 8080）。
 */
test('报表详情页：图表切换 / AI 洞察 / 表格展示', async ({ page, request }) => {
  await page.addInitScript(() => localStorage.setItem('hrchat_user_no', 'hr01'))

  // 创建报表（编制盘点模板：CHART + TABLE 组件）
  const resp = await request.post('/api/v1/report-templates/tpl-headcount:instantiate', {
    headers: { 'X-User-No': 'hr01' },
    data: { params: {}, name: `E2E 详情 ${Date.now()}` },
  })
  expect(resp.ok()).toBeTruthy()
  const body = (await resp.json()) as { data?: number }
  const reportId = body.data
  expect(reportId).toBeGreaterThan(0)

  await page.goto(`/reports/${reportId}`)
  await expect(page.locator('.detail-page .report-title').first()).toBeVisible()

  // 图表类型一键切换（CHART 组件：柱状 → 折线）
  await page.locator('.chart-toolbar .ant-radio-button-wrapper').getByText('折线').click()
  await expect(page.locator('.echart-container').first()).toBeVisible()

  // AI 洞察卡片出现并含摘要与要点
  await expect(page.locator('.insight-card').first()).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.insight-summary').first()).toBeVisible()
  await expect(page.locator('.insight-points li').first()).toBeVisible()

  // 明细表组件渲染数据行
  await expect(page.locator('.ant-table-row').first()).toBeVisible()
})
