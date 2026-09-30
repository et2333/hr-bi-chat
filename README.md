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

默认配置使用 H2 内存数据库、模拟身份和 Java 本地问数引擎，因此启动前后端即可体验主要功能。

### 1. 启动后端

```powershell
cd hrchat-server
mvn -pl hrchat-bootstrap -am package -DskipTests
java -jar .\hrchat-bootstrap\target\hrchat-bootstrap-1.0.0-SNAPSHOT.jar
```

后端地址：http://localhost:8080  
Swagger：http://localhost:8080/swagger-ui.html  
健康检查：http://localhost:8080/actuator/health

### 2. 启动前端

另开一个终端：

```powershell
cd hrchat-web
pnpm install --frozen-lockfile
pnpm dev
```

浏览器访问 http://localhost:5173。默认演示身份为 `hr01`，可在个人中心切换为管理员 `adm01`。

## 可选：启动 Python AI 服务

```powershell
cd hrchat-ai
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
$env:LLM_PROFILE = "mock"
.\.venv\Scripts\python.exe -m uvicorn agent_gateway.app:app --host 127.0.0.1 --port 8000
```

随后重启 Java 后端并启用远程运行时：

```powershell
java -jar .\hrchat-bootstrap\target\hrchat-bootstrap-1.0.0-SNAPSHOT.jar `
  --hrchat.ai.runtime=remote `
  --hrchat.ai.remote-base-url=http://localhost:8000
```

## 常用配置

| 环境变量 | 用途 | 默认值 |
|---|---|---|
| `HRCHAT_PUSH_OUTPUT_DIR` | 订阅推送附件输出目录 | `./push-out` |
| `LLM_PROFILE` | Python AI 模型配置，支持 `mock` 或 `openai` | `mock` |
| `OPENAI_BASE_URL` | OpenAI 兼容接口地址 | `https://api.openai.com/v1` |
| `OPENAI_API_KEY` | OpenAI 兼容接口密钥 | 未设置 |
| `OPENAI_MODEL` | 模型名称 | `gpt-4o-mini` |

本地 H2 数据会在后端停止后清空，重启时自动载入演示数据。更多信息参见 [项目文档](docs/) 和 [开发计划](docs/development-plans/)。
