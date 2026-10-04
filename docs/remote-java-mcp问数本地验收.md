# remote + Java MCP 问数：本地手动验收

目标：验证显式 `hrchat.ai.runtime=remote` 时，问数走 Python → Java `/mcp`，权限与取数以 Java 为单一事实源；不回退 Python 演示假数。

对照方案：[1001权限单一事实源与Java-Python问数链路收敛实施方案](development-plans/1001权限单一事实源与Java-Python问数链路收敛实施方案.md) 阶段 E / §9。

## 1. 准备

1. 按 [README](../README.md#可选启动-python-ai-服务) 先起 Python（`QUERY_BACKEND=java_mcp` + MCP 环境变量），再起 Java（`--hrchat.ai.runtime=remote`），最后起前端。
2. 三个进程均需保持运行；Java 使用 H2，**重启会清空内存数据**。
3. 默认租户 `t01`。演示身份：`hr01`（可查研发中心）、`hr02`（不可查研发中心）。
4. 后台若存在 ACTIVE 远程模型部署配置，会优先于命令行 `runtime=remote`；本验收请勿一键部署干扰，或先回滚/停用 ACTIVE 配置。

## 2. 页面验收

| 步骤 | 操作 | 预期 |
|---|---|---|
| ① 允许查询 | 身份 `hr01`，问「研发中心在职人数」 | 结论有人数（H2 种子下常见为 **18**）；有口径；「查看 SQL」可打开且含 `askId` 对应 SQL |
| ② 越权 | 身份 `hr02`，同一问句 | **不是** HTTP 500 / HRS-3001；答案区为业务错误 **HRC-2003**（或越权态），无 Demo 假数（如 1275） |
| ③ 发送清空 | `hr01` 输入框发送任意成功问句 | 发送后输入框清空；对话区有用户气泡与回复 |
| ④ 追问占位 | 点击「查看明细」等追问 chips | **本期不验收通过**：可能 HRA-4001。按钮为占位，多轮上下文后续迭代；勿据此判 remote 失败 |

## 3. 接口抽查（可选）

PowerShell（注意 `-UseBasicParsing`，避免非交互卡住）：

```powershell
$ProgressPreference = 'SilentlyContinue'
$askBytes = [System.Text.Encoding]::UTF8.GetBytes('{"question":"研发中心在职人数","mode":"STREAM"}')

# hr01
$s1 = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/chat/sessions' -Method Post `
  -Headers @{ 'X-User-No'='hr01'; 'X-Tenant-No'='t01'; 'Content-Type'='application/json' } `
  -Body '{"title":"e2e-hr01"}'
$r1 = Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v1/chat/sessions/$($s1.data.id)/asks" -Method Post `
  -Headers @{ 'X-User-No'='hr01'; 'X-Tenant-No'='t01'; 'Content-Type'='application/json; charset=utf-8'; 'Accept'='text/event-stream' } `
  -Body $askBytes -TimeoutSec 120 -UseBasicParsing
# 期望 HTTP 200，正文含 ANSWER_DONE 与 askId

# hr02
$s2 = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/chat/sessions' -Method Post `
  -Headers @{ 'X-User-No'='hr02'; 'X-Tenant-No'='t01'; 'Content-Type'='application/json' } `
  -Body '{"title":"e2e-hr02"}'
$r2 = Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v1/chat/sessions/$($s2.data.id)/asks" -Method Post `
  -Headers @{ 'X-User-No'='hr02'; 'X-Tenant-No'='t01'; 'Content-Type'='application/json; charset=utf-8'; 'Accept'='text/event-stream' } `
  -Body $askBytes -TimeoutSec 120 -UseBasicParsing
# 期望 HTTP 200，正文含 ERROR 与 HRC-2003，无 HRS-3001
```

空闲时探测 MCP（应 200 + 业务错，证明入口存活；token 用 dummy 时期望 HRC-2001）：

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/mcp' -Method Post `
  -Headers @{ 'X-Service-Token'='local-dev-mcp-service-token'; 'Content-Type'='application/json' } `
  -Body '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'
```

## 4. 自动化已覆盖（不必手验重复）

- Java：`ToolContextTokenServiceTest`（过期/错 audience/跨 invocation）、`McpAuthServiceTest`、`McpControllerTest`、`SemanticQueryServiceTest`
- Python：`test_mcp_client.py`（HRC-2003 不重试、服务令牌头）、`test_ask_flow_java_mcp.py`（不回退 Demo 1275）
- 前端：`askId` snake_case 兼容与发送清空（`hrchat-web` chat 页）

## 5. 明确不在本期手验范围

- 追问下钻/对比/趋势多轮上下文
- `report_save` MCP 工具
- 生产 mTLS / Secret 轮换 / 多副本令牌撤销
- 归因团队改走 MCP（仍可依赖 demo 字典，非正式问数）
