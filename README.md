# HR BI Chat

HR BI Chat 是一个面向人力资源场景的智能问数与报表平台，包含自然语言问数、指标与权限管理、报表展示以及可选的 AI Agent 运行时。

## 项目结构

```text
hr-bi-chat/
├── hrchat-web/          # Vue 3 + TypeScript 前端
├── hrchat-server/       # Java 17 + Spring Boot 多模块后端
├── hrchat-ai/           # Python FastAPI AI 运行时（可选）
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
- Python 3.11+（仅运行 `hrchat-ai` 时需要）

## 本地启动

默认配置使用 H2 内存数据库、模拟身份和 Java 本地问数引擎，启动前后端即可体验主要功能；无需安装 MySQL、Redis、Python 或配置模型密钥。

以下使用 Windows PowerShell。每个新终端先进入仓库根目录（例如 `Set-Location D:\et-repo\bi-chat`），再执行对应命令。Java/Node 不需要 Python 虚拟环境；前端依赖安装在项目 `node_modules` 中。

### 1. 启动后端

```powershell
cd hrchat-server
mvn -pl hrchat-bootstrap -am package "-DskipTests"
java -jar .\hrchat-bootstrap\target\hrchat-bootstrap-1.0.0-SNAPSHOT.jar --spring.profiles.active=local --hrchat.security.mode=mock --hrchat.ai.runtime=local --server.address=127.0.0.1 --server.port=8080
```

后端地址：http://localhost:8080  
Swagger：http://localhost:8080/swagger-ui.html  
健康检查：http://localhost:8080/actuator/health

构建成功后再执行 `java`；更新 Java 代码或迁移脚本后需要重新构建并重启。启动时 Flyway 自动执行 V1～V3，创建表、加载演示身份及默认角色权限，不要再手动导入 SQL。保持该终端运行，`Ctrl+C` 停止服务。

### 2. 启动前端

另开一个终端，从仓库根目录执行（不要在 `hrchat-server` 内执行 `cd hrchat-web`）：

```powershell
cd hrchat-web
pnpm install --frozen-lockfile
pnpm dev --host 127.0.0.1 --port 5173 --strictPort
```

浏览器访问 http://localhost:5173。前端将 `/api` 请求代理到后端 8080 端口。首次访问默认演示身份为 `hr01`，之后会记住上次身份；可通过右上角下拉菜单或个人中心切换为管理员 `adm01`。

端口被占用时先确认是否已有项目服务运行，不要重复启动。mock 身份不是登录认证，仅用于本机演示，不要将服务暴露到公网。

## 手动验证功能权限

参见 [统一功能权限来源：本地手动验收](docs/统一功能权限来源本地验收.md)。推荐使用新角色和 `test09`，依次验证“无权限 → 授权 → 能访问 → 撤权 → 403”，不修改默认管理员角色。

## 可选：启动 Python AI 服务

本次功能权限验收不需要此服务。若要单独体验 Python 运行时，另开终端，从仓库根目录执行；以下直接使用虚拟环境解释器，无需激活环境：

```powershell
cd hrchat-ai
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
$env:LLM_PROFILE = "mock"
.\.venv\Scripts\python.exe -m uvicorn agent_gateway.app:app --host 127.0.0.1 --port 8000
```

Python 健康检查：http://localhost:8000/health。随后在原 Java 终端停止后端（`Ctrl+C`），确认当前目录为 `hrchat-server`，再启动远程运行时：

```powershell
java -jar .\hrchat-bootstrap\target\hrchat-bootstrap-1.0.0-SNAPSHOT.jar `
  --spring.profiles.active=local `
  --hrchat.security.mode=mock `
  --server.address=127.0.0.1 `
  --server.port=8080 `
  --hrchat.ai.runtime=remote `
  --hrchat.ai.remote-base-url=http://localhost:8000
```

注意：Python 当前仍使用独立的 `DemoQueryExecutor`，尚未统一到 Java 的数据执行链路，不能用它替代 Java 数据权限验收。后台 ACTIVE 模型部署配置优先于 `hrchat.ai.runtime`；测试默认本地链路时不要点击模型“一键部署”。

## 常用配置

| 环境变量 | 用途 | 默认值 |
|---|---|---|
| `HRCHAT_PUSH_OUTPUT_DIR` | 订阅推送附件输出目录 | `./push-out` |
| `HRCHAT_MCP_SERVICE_TOKEN` | Python→Java `/mcp` 服务令牌（禁止提交真实值） | `local-dev-mcp-service-token` |
| `HRCHAT_TOOL_TOKEN_SECRET` | Java 签发短期工具 JWT 的 HS256 密钥（≥32 字节） | `local-dev-tool-token-secret-32b` |
| `JAVA_MCP_BASE_URL` | Python 调用 Java MCP 的地址（阶段 D 客户端使用） | `http://127.0.0.1:8080/mcp` |
| `LLM_PROFILE` | Python AI 模型配置，支持 `mock` 或 `openai` | `mock` |
| `OPENAI_BASE_URL` | OpenAI 兼容接口地址 | `https://api.openai.com/v1` |
| `OPENAI_API_KEY` | OpenAI 兼容接口密钥 | 未设置 |
| `OPENAI_MODEL` | 模型名称 | `gpt-4o-mini` |

本地 H2 数据会在后端停止后清空，重启时自动载入演示数据。更多信息参见 [项目文档](docs/) 和 [开发计划](docs/development-plans/)。
