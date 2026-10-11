import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

/**
 * I3-8 报表来源进一步分析联调：动作推荐 → 确认 → 创建 → 进度 → 结果 → 收起恢复 → 取消。
 *
 * 由 evals.s6_smoke --browser 驱动（fresh H2 + 脚本模型，无付费 API）：先经问答生成一张
 * leave_count 报表（继承受信 context），再从报表详情页的推荐动作打开侧边分析面板。
 */

const IDENTITY = { 'X-User-No': 'hr04', 'X-Tenant-No': 't01' }

/** 造一张继承问答受信上下文的 leave_count 报表（仅 ASK 来源带 context，才有可点击推荐动作）。 */
async function createLeaveCountReport(request: APIRequestContext): Promise<number> {
  const session = await request.post('/api/v1/chat/sessions', {
    headers: IDENTITY,
    data: { title: 'I3-8 report analysis' },
  })
  expect(session.ok()).toBeTruthy()
  const sessionId = (await session.json()).data.id as number

  const ask = await request.post(`/api/v1/chat/sessions/${sessionId}/asks`, {
    headers: IDENTITY,
    data: { question: '上月研发中心人员流失人数', mode: 'SYNC' },
  })
  expect(ask.ok()).toBeTruthy()
  const askId = (await ask.json()).askId as string
  expect(askId, 'ask 未返回 askId').toBeTruthy()

  const report = await request.post('/api/v1/reports', {
    headers: IDENTITY,
    data: { sourceType: 'ASK', sourceId: askId, name: `I3-8 分析 ${Date.now()}` },
  })
  expect(report.ok()).toBeTruthy()
  const reportId = (await report.json()).data as number
  expect(reportId).toBeGreaterThan(0)
  return reportId
}

/** 统计报表分析任务创建请求（用于断言收起/重开不会新建任务）。 */
function countAnalysisStarts(page: Page): () => number {
  let starts = 0
  page.on('request', (req) => {
    if (req.method() === 'POST' && /\/api\/v1\/reports\/\d+\/analysis$/.test(new URL(req.url()).pathname)) {
      starts += 1
    }
  })
  return () => starts
}

test('报表侧边进一步分析：推荐动作→确认→进度→结果→收起恢复→取消', async ({ page, request }) => {
  test.skip(process.env.S6_FIXTURE_E2E !== '1', 'Requires isolated S6 fixture services')

  await page.addInitScript(() => {
    localStorage.setItem('hrchat_user_no', 'hr04')
    localStorage.setItem('hrchat_tenant_no', 't01')
  })
  const analysisStarts = countAnalysisStarts(page)

  const reportId = await createLeaveCountReport(request)
  await page.goto(`/reports/${reportId}`)
  await expect(page.locator('.detail-page .report-title').first()).toBeVisible()

  // 1) 动作推荐：AI 洞察给出可点击的「进一步分析」动作
  const action = page.locator('.insight-actions .action-clickable').first()
  await expect(action).toBeVisible({ timeout: 30_000 })
  await expect(action).toHaveAttribute('title', '点击在侧边面板中确认并分析')

  // 2) 打开侧边面板：显示来源版本与预填范围（留在报表页，不创建聊天会话）
  await action.click()
  await expect(page.getByTestId('analysis-form').last()).toBeVisible()
  await expect(page.getByTestId('current-period').last()).not.toBeEmpty()
  await expect(page.getByTestId('baseline-start').last()).not.toHaveValue('')

  // 3) 确认两期 → 创建任务 → 进度
  await page.getByTestId('confirm-analysis').last().click()
  await expect(page.locator('.analysis-stages').last()).toBeVisible({ timeout: 30_000 })
  const startsAfterConfirm = analysisStarts()
  expect(startsAfterConfirm).toBe(1)

  // 4) 结果：部门贡献表（本期/基期/变化）
  await expect(page.getByTestId('analysis-summary').last()).toContainText('本期', { timeout: 30_000 })
  await expect(page.getByTestId('analysis-summary').last()).toContainText('变化')
  await expect(page.locator('.contribution-table').last()).toBeVisible()

  // 5) 收起：仅停止展示（侧边面板遮罩消失，页面恢复可交互），不取消、不新建任务
  const collapse = page.getByRole('button', { name: /收\s*起/ }).last()
  await collapse.click()
  await expect(page.locator('.ant-drawer-mask').first()).toBeHidden()

  // 6) 收起后切换主图：报表主图可正常交互，且不影响已完成的分析任务
  await page.locator('.chart-toolbar .ant-radio-button-wrapper').getByText('折线').click()

  // 7) 重开：按任务 ID 恢复结果，不新建任务
  await action.click()
  await expect(page.getByTestId('analysis-summary').last()).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.contribution-table').last()).toBeVisible()
  expect(analysisStarts()).toBe(startsAfterConfirm)

  // 8) 取消：重新选择基期创建新任务并停止后续工作
  await page.getByRole('button', { name: /重新选择基期/ }).last().click()
  await expect(page.getByTestId('analysis-form').last()).toBeVisible()
  await page.getByTestId('confirm-analysis').last().click()
  const cancelButton = page.getByRole('button', { name: /取消分析/ }).last()
  await expect(cancelButton).toBeVisible({ timeout: 15_000 })
  await cancelButton.click()
  await expect(page.locator('.analysis-result').last()).toContainText('已取消')
})
