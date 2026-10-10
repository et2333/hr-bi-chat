# HR BI Chat

HR BI Chat 是面向人力资源场景的 AI 问数原型：模型理解自然语言，Java 按权威口径与权限取数；支持多轮澄清、趋势/分组/明细，以及证据约束的双 Agent 变化分析。

## 项目结构

```text
hr-bi-chat/
├── hrchat-web/          # Vue 3 + TypeScript 前端
├── hrchat-server/       # Java 17 + Spring Boot 多模块后端
├── hrchat-ai/           # Python FastAPI + LangGraph AI 运行时
├── hrchat-deploy/       # 数据库脚本及部署资源
├── docs/                # 产品、架构、接口和部署文档
│   └── development-plans/ # 开发计划与阶段方案
└── .github/             # GitHub 配置
```

## 环境要求

- Java 17
- Maven 3.9+
- Node.js 20+
- pnpm 9.1.3
- Python 3.11+

## 本地启动

默认演示使用真实 LLM：浏览器 → Java → Python Agent → Java 授权工具 → H2 模拟数据。请保持下面三个终端运行。模型只理解问题与生成受约束的计划，业务数据由 Java 查询。

**首次准备**：在 `hrchat-ai` 中安装 Python 依赖，将 `.env.example` 复制为 `.env.local`，填入自己的 Key、Base URL 和模型名称（建议 `qwen-plus`）。已有 `.env.local` 不要覆盖；Key 不提交 Git。以下每条命令均为单行，在仓库根目录开始。

```powershell
cd hrchat-ai
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install torch --index-url https://download.pytorch.org/whl/cpu
.\.venv\Scripts\python.exe -m pip install -e ".[dev,retrieval]"
.\.venv\Scripts\python.exe -m adapters.prepare_embedding
```


### 1. 终端A：启动 Python AI 服务

```powershell
cd hrchat-ai
.\.venv\Scripts\python.exe -m agent_gateway.demo
```

此入口读取 `.env.local`，强制使用 `openai + java_mcp`，默认启用本地中文 RAG；缺少配置或模型文件时直接提示。embedding 下载到 `.models/`（不提交 Git），问数时在 CPU 本地运行。启动不发起付费推理，实际提问会调用配置的 LLM。仅使用本机模拟数据。Python 健康检查：http://127.0.0.1:8000/health?tenant_no=t01。

### 2. 终端B：启动后端

```powershell
cd hrchat-server
mvn -pl hrchat-bootstrap -am package "-DskipTests"
java -jar .\hrchat-bootstrap\target\hrchat-bootstrap-1.0.0-SNAPSHOT.jar --spring.profiles.active=local --hrchat.security.mode=mock --hrchat.ai.runtime=remote --hrchat.ai.remote-base-url=http://127.0.0.1:8000 --server.address=127.0.0.1 --server.port=8080
```

后端地址：http://localhost:8080  
Swagger：http://localhost:8080/swagger-ui.html  
健康检查：http://localhost:8080/actuator/health

构建成功后再执行 `java`；已有后端先 Ctrl+C 停止。更新 Java 代码后需重新构建，更新 Python 配置后需重启 Python。H2 是内存库，重启 Java 会清空会话，请先保存试用记录。后台 ACTIVE 部署可覆盖启动参数，手动演示期间不要点击模型“一键部署”。离线规则基线必须显式指定 `--hrchat.ai.runtime=local`，它不具备 AI 多轮能力，不作为默认演示。

演示前执行这一行，只读取实际运行时、不调用模型：

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/chat/runtime' -Headers @{'X-User-No'='hr01'} | ConvertTo-Json -Depth 4
```

应看到 `runtime=remote`、`llm_profile=openai`、`query_backend=java_mcp`、`rag_mode=hybrid` 和你的模型名称；若是 `local/mock`，先核对旧服务与后台 ACTIVE 配置。

### 3. 终端C：启动前端

```powershell
cd hrchat-web
pnpm install --frozen-lockfile
pnpm dev --host 127.0.0.1 --port 5173 --strictPort
```

浏览器访问 http://localhost:5173。前端将 `/api` 请求代理到后端 8080 端口。首次访问默认演示身份为 `hr01`，之后会记住上次身份；可通过右上角下拉菜单或个人中心切换为管理员 `adm01`。

### 4. 手动演示顺序

使用 `hr01` 新建会话；模拟业务日期固定为 **2026-09-28**，“上月”为 8 月。当前 AI 查询指标为在职人数、入职人数、离职人数；薪酬、离职率、任意筛选不在当前能力范围内。

| 操作 | 核对点 |
|---|---|
| 输入“上月研发中心在职人数” | 17 人；条件为研发中心含下级、2026 年 8 月 |
| 原会话输入“研发一部呢？” | 10 人；保留指标和期间 |
| 输入“本月？” | 只更新期间；不能变成薪酬指标 |
| 新会话输入“研发中心离职人数”，选择“上月” | 保留系统询问、实际选择；答案卡展示最终条件，刷新页面仍可读 |
| 输入“查看明细”或“按部门对比” | 明细表或部门分组，不能悄悄返回单值 |
| 完成单期离职人数查询后输入“分析变化”，或点击同名按钮 | 自动打开两期确认；确认后才开始 Analyst/Reviewer 分析；展示统计贡献及证据边界 |
| 新会话输入“按组织对比平均薪酬”或“薪资对比” | 明确提示暂不支持；不能换成人数、人力成本或单值回答 |
| 观察普通问数过程 | 阶段在执行时陆续出现；结束后显示模型调用次数和 Token，未配置价格则金额标为未知 |

记录失败时保留原始问句、澄清选择、实际回答与期望，连续追问按完整会话记录。离线回归与真实模型验收说明见 [评测 README](hrchat-ai/evals/README.md)。

**可选 J2 同名部门演示**：将终端 B 启动命令中的 `--spring.profiles.active=local` 改为 `--spring.profiles.active=local,j2`，重启 Java 后新建会话，输入“研发部离职人数”。应先选择带上级路径和编码的部门，再选择“上月”；答案和历史保留实际采用的部门。两个演示部门没有员工数据，结果为 0，重点验证消歧和条件保留。普通启动不加载这些部门，旧 S1/J1/S6 评测继续使用原组织集合。






E2E 需先按“本地启动”启动 8080 端口的 Java local/mock 服务；首次运行还需执行 `pnpm exec playwright install chromium`：

```powershell
cd hrchat-web
pnpm exec playwright test e2e/ask.spec.ts e2e/permission.spec.ts e2e/report.spec.ts
```

三条 E2E 分别验证正常问数、越权拦截和报表创建。

## 常用配置

| 环境变量 | 用途 | 默认值 |
|---|---|---|
| `HRCHAT_PUSH_OUTPUT_DIR` | 订阅推送附件输出目录 | `./push-out` |
| `HRCHAT_MCP_SERVICE_TOKEN` | Python→Java `/mcp` 服务令牌（禁止提交真实值） | `local-dev-mcp-service-token` |
| `HRCHAT_TOOL_TOKEN_SECRET` | Java 签发短期工具 JWT 的 HS256 密钥（≥32 字节） | `local-dev-tool-token-secret-32b` |
| `JAVA_MCP_BASE_URL` | Python 调用 Java MCP 的地址 | `http://127.0.0.1:8080/mcp` |
| `QUERY_BACKEND` | Python 取数后端：`java_mcp`（正式）/ `demo`（假数据） | `java_mcp` |
| `LLM_PROFILE` | Python AI 模型配置，支持 `mock` 或 `openai` | `openai`；离线测试显式 mock |
| `HRCHAT_RAG_MODE` | 本地口径/开发示例检索，支持 `hybrid` / `lexical` / `off` | demo 入口默认 `hybrid` |
| `OPENAI_BASE_URL` | OpenAI 兼容接口地址 | `https://api.openai.com/v1` |
| `OPENAI_API_KEY` | OpenAI 兼容接口密钥 | 未设置 |
| `OPENAI_MODEL` | 模型名称 | `gpt-4o-mini` |

本地 H2 数据会在后端停止后清空，重启时自动载入演示数据。

RAG 使用当前 Java 可见目录、人工编写的查询草稿示例和本地 BGE 中文 embedding，混合 BM25 与向量排序；目录中的不可执行口径仍可用于说明边界，不能据此取数。不检索员工结果或历史 SQL。当前保留完整目录作为约束，尚未证明 Token 节省或最终问数提升。配置、离线对照及真实模型待验收项见[评测说明](hrchat-ai/evals/README.md#本地-rag与对话体验验收j3j4j5)。
