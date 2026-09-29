# HR智能问数 部署手册

| 文档属性 | 内容 |
|---|---|
| 产品名称 | HR智能问数（HR ChatBI） |
| 文档编号 | DEPLOY-HRCHATBI-001 |
| 版本号 | V1.0 |
| 文档状态 | 发布稿 |
| 适用版本 | HR智能问数 V1.0（应用镜像 tag `1.0.x`） |
| 关联文档 | 《应用架构ARCH-HRCHATBI-001》《数据库设计DB-HRCHATBI-001》《技术选型TECH-HRCHATBI-001》 |
| 创建日期 | 2026-09-13 |
| 作者 | 架构组/SRE |
| 密级 | 内部 |

### 修订历史

| 版本 | 日期 | 修订人 | 修订说明 |
|---|---|---|---|
| V1.0 | 2026-09-13 | SRE | 首次发布 |

### 阅读指引（按人员角色）

| 角色 | 必读章节 | 选读 |
|---|---|---|
| 实施工程师（首次安装） | §2 §3 §4 §5 §7 | §8 |
| 运维工程师（日常运维） | §7 §8 §9 §10 | 全部 |
| 开发工程师（联调环境） | §4.4 §5 §6 | §3 |
| 安全工程师 | §6 | §2.4 |

### 使用约定

- 命令块中 `<占位符>` 需替换为实际值；`#` 开头为注释
- 「⚠️」标记安全关键步骤，「🔍」标记验证点，「📦」标记检查清单产出物
- 所有操作默认在部署管理机（运维跳板）执行，非特别说明不在K8s Master直接操作

---

## 目录

1. [系统环境要求](#1-系统环境要求)
2. [部署架构与拓扑](#2-部署架构与拓扑)
3. [前置条件检查](#3-前置条件检查)
4. [详细部署步骤](#4-详细部署步骤)
5. [环境变量配置说明](#5-环境变量配置说明)
6. [安全配置指南](#6-安全配置指南)
7. [监控告警设置](#7-监控告警设置)
8. [部署验证步骤](#8-部署验证步骤)
9. [常见问题排查与解决方案](#9-常见问题排查与解决方案)
10. [回滚机制](#10-回滚机制)
11. [版本更新流程](#11-版本更新流程)
12. [附录](#12-附录)

---

## 1. 系统环境要求

### 1.1 硬件资源要求

| 节点角色 | 数量 | CPU | 内存 | 磁盘 | GPU | 用途 |
|---|---|---|---|---|---|---|
| K8s Master | 3 | 8C | 16G | 200G SSD | — | 控制面（etcd建议独立盘） |
| K8s Worker-通用 | ≥6 | 16C | 64G | 500G SSD | — | 应用/AI运行时/中间件 |
| K8s Worker-GPU | 2 | 16C | 64G | 500G SSD | 2×A800 80G（72B推理） | vLLM主模型 |
| K8s Worker-GPU | 1 | 16C | 32G | 300G SSD | 1×A10 24G | 14B/Embedding |
| 中间件节点（可混部Worker） | 4 | 16C | 128G | 2T SSD×2 | — | Doris BE（数据盘独立） |
| 部署管理机（跳板） | 1 | 4C | 8G | 100G | — | Helm/kubectl执行 |

> 最小化演示环境（POC）：3节点（1 Master+2 Worker，其中1台带A10）可运行，仅用于功能验证，不满足生产SLO。

### 1.2 软件版本要求

| 软件 | 版本 | 硬性要求 | 说明 |
|---|---|---|---|
| 操作系统 | Ubuntu 22.04 LTS / Rocky Linux 9 | 是 | 内核≥5.15（GPU/容器要求） |
| Kubernetes | 1.28+ | 是 | 需启用 GPU device plugin（NVIDIA k8s-device-plugin 0.14+） |
| 容器运行时 | containerd 1.7+ | 是 | |
| NVIDIA驱动 | 535+ / CUDA 12.1+ | GPU节点必须 | `nvidia-smi`验证 |
| Helm | 3.14+ | 是 | 部署唯一工具 |
| kubectl | 与K8s小版本匹配±1 | 是 | |
| Docker（仅管理机） | 24+ | 构建镜像用 | |
| MySQL | 8.0.36+ | 是 | utf8mb4、半同步复制 |
| Apache Doris | 2.1.x | 是 | FE×3 + BE×4（生产） |
| Redis | 7.2+ | 是 | 哨兵或单分片Cluster |
| Kafka | 3.6+ | 是 | 3节点 |
| PostgreSQL | 16+ | 是 | 含pgvector扩展 |
| MinIO | RELEASE.2024+ | 是 | 纠删码4+2 |
| Harbor | 2.9+ | 私有化必须 | 镜像仓库 |
| GitLab CI Runner | 16+ | 持续集成用 | 可与企业已有共用 |

### 1.3 网络与端口要求

| 端口 | 协议 | 方向 | 组件 | 用途 |
|---|---|---|---|---|
| 443 | TCP | 入（DMZ→） | APISIX/Ingress | HTTPS唯一入口 |
| 6443 | TCP | 管理→Master | K8s API | kubectl/Helm |
| 3306/3306 | TCP | 集群内 | MySQL | 主从复制+服务 |
| 9030/8030 | TCP | 集群内 | Doris FE | MySQL协议/RPC |
| 9060/8040 | TCP | 集群内 | Doris BE | 心跳/数据 |
| 6379/26379 | TCP | 集群内 | Redis | 服务/哨兵 |
| 9092 | TCP | 集群内 | Kafka | 消息 |
| 5432 | TCP | 集群内 | PostgreSQL | 检查点/向量 |
| 9000/9001 | TCP | 集群内 | MinIO | API/控制台 |
| 8000 | TCP | 集群内 | vLLM | OpenAI兼容API |
| 10250 | TCP | 集群内 | kubelet | 监控采集 |

**网络策略基线（⚠️）**：
- AI运行时命名空间 **NetworkPolicy 默认拒绝出网**（仅放行vLLM、PG、Redis、Kafka、MinIO、Java服务）——红线：数据不出域
- DMZ区仅暴露443；数据库节点不允许直接对外
- GPU节点需能访问企业内部镜像仓库（拉取vLLM镜像）或预加载镜像

### 1.4 外部依赖清单

| 依赖 | 用途 | 缺失影响 | 提供方 |
|---|---|---|---|
| 企业SSO（OIDC） | 登录认证 | 无法登录（阻塞） | 企业IAM团队 |
| SMTP网关 | 报表邮件订阅 | 邮件推送不可用（非阻塞） | IT |
| IM开放平台应用（企微/钉钉/飞书） | IM订阅推送 | IM推送不可用（非阻塞） | IT+对应平台审批 |
| 源系统只读账号（HRIS等5套） | 数据抽取 | 无数据（阻塞） | 各源系统团队 |
| DNS记录 | 域名解析 | 无法访问 | IT |

---

## 2. 部署架构与拓扑

### 2.1 生产部署架构图

```
                              Internet/企业内网用户
                                      │ HTTPS 443
                        ┌─────────────▼──────────────┐
                        │  DMZ区: LB(VIP)             │
                        │  APISIX×2 (DaemonSet/双活)  │
                        └─────────────┬───────────────┘
                 ┌────────────────────┼───────────────────────┐
        ┌────────▼─────────┐ ┌───────▼────────┐ ┌─────────────▼──────┐
        │ 命名空间: hrchat-app│ │ hrchat-ai      │ │ hrchat-middleware  │
        │  web-app(Nginx)×2 │ │ agent-gateway×3 │ │ MySQL主从(2)       │
        │  java-app×3       │ │ langgraph-rt×2  │ │ Redis哨兵(3)       │
        │   (模块化单体)      │ │ agentscope-rt×2 │ │ Kafka(3)           │
        │  mcp-server×2     │ │ sandbox-pool    │ │ PostgreSQL(1主1备) │
        └───────────────────┘ │ Langfuse        │ │ MinIO(4+2纠删)     │
               │              └───────┬─────────┘ └────────────────────┘
               │                      │ 只读查询(MySQL协议)
        ┌──────▼──────────────────────▼───────────────────┐
        │ 命名空间: hrchat-olap                             │
        │  Doris FE×3 (1 Follower+2 Observer)              │
        │  Doris BE×4 (12C128G/2T, 数据盘独立挂载)           │
        └──────────────────────┬───────────────────────────┘
        ┌──────────────────────▼───────────────────────────┐
        │ 命名空间: hrchat-gpu                               │
        │  vllm-qwen72b (2×A800, AWQ量化, 副本1-2)           │
        │  vllm-qwen14b + bge-m3 (1×A10)                    │
        └───────────────────────────────────────────────────┘
        ┌───────────────────────────────────────────────────┐
        │ 命名空间: hrchat-observe                           │
        │  Prometheus×2(HA) │ Grafana │ Alertmanager×2      │
        │  SkyWalking OAP │ ELK(ES×3+Kibana) │ Loki(可选)   │
        └───────────────────────────────────────────────────┘
        集成层（hrchat-integration命名空间）:
          DataX(Job式) │ Flink CDC(TaskManager×2) │ XXL-Job Admin
```

### 2.2 命名空间规划

| 命名空间 | 内容 | 网络策略 |
|---|---|---|
| hrchat-app | 前端/Java应用/MCP | 限入（仅网关+ai），允许出至middleware/olap |
| hrchat-ai | AI运行时 | 限入（仅app），**默认拒绝出网**⚠️ |
| hrchat-middleware | 数据库/缓存/消息 | 限入（按端口），禁止对外 |
| hrchat-olap | Doris | 限入（app/ai/integration） |
| hrchat-gpu | vLLM | 限入（仅ai/observe） |
| hrchat-observe | 可观测组件 | 采集需要，放宽入向 |
| hrchat-integration | 数据集成 | 允许出至源系统白名单IP段 |

### 2.3 镜像与Chart清单

| 镜像 | Chart | 说明 |
|---|---|---|
| registry.internal/hrchat/web-app:1.0.x | hrchat-web | React构建产物+Nginx |
| registry.internal/hrchat/java-app:1.0.x | hrchat-server | 模块化单体（含mcp-server profile） |
| registry.internal/hrchat/agent-gateway:1.0.x | hrchat-ai-runtime | FastAPI+LangGraph+AgentScope（单镜像多进程） |
| registry.internal/hrchat/vllm-openai:v0.6.x-qwen72b-awq | hrchat-vllm | 基于vLLM官方镜像+模型权重预置 |
| 中间件镜像 | 依赖子Chart | bitnami/官方Chart固定版本 |

> 私有化交付包（offline bundle）：Helm Charts + 全量镜像tar + 模型权重（约140GB）+ 种子SQL，见§4.1。

---

## 3. 前置条件检查

### 3.1 检查清单（逐项执行并记录）

```bash
# ===== 1. K8s集群 =====
kubectl get nodes -o wide                       # 🔍所有节点Ready，版本≥1.28
kubectl top nodes                                # 🔍资源余量≥30%
kubectl get cs                                   # scheduler/controller-manager健康
kubectl get pods -n kube-system | grep -v Running  # 🔍应为空

# ===== 2. GPU节点 =====
nvidia-smi                                       # 🔍驱动≥535，GPU识别正常（GPU节点执行）
kubectl describe node <gpu-node> | grep -A5 nvidia.com/gpu
                                                 # 🔍可分配GPU数正确

# ===== 3. 存储 =====
kubectl get sc                                   # 🔍存在SSD StorageClass
# Doris/MySQL需本地盘或高性能PV，检查挂载点
df -h /data                                      # 🔍数据盘容量（BE节点≥2T）

# ===== 4. DNS与证书 =====
dig hrchat.example.com                           # 🔍解析到LB VIP
openssl x509 -in server.crt -noout -enddate      # 🔍证书有效期>90天

# ===== 5. 网络连通性（从管理机） =====
for h in <mysql节点> <kafka节点> <源系统IP段>; do
  timeout 3 bash -c "echo >/dev/tcp/$h/3306" && echo "$h OK" || echo "$h FAIL"
done

# ===== 6. 外部依赖 =====
# OIDC发现端点
curl -s https://sso.example.com/.well-known/openid-configuration | jq .issuer
# SMTP
timeout 5 bash -c "echo >/dev/tcp/<smtp-host>/25" && echo "SMTP OK"

# ===== 7. 私有仓库 =====
docker login registry.internal -u deploy-bot     # 🔍凭证有效
helm repo list                                   # 🔍hrchat私有repo已添加
```

### 3.2 检查清单产出物（📦）

| 产出物 | 填写内容 |
|---|---|
| 《部署检查表》 | 上述各项OK/FAIL与截图 |
| 资源分配表 | 节点IP→角色→命名空间调度约束 |
| 域名证书信息 | 域名、有效期、SAN覆盖 |
| 源系统账号确认单 | 5套源系统账号权限（只读）验证结果 |

**准入规则**：任何FAIL项不解决不得进入§4（源系统账号可延期，但会阻塞数据初始化§4.3.5）。

---

## 4. 详细部署步骤

### 4.1 获取部署包

```bash
# 在线环境
helm repo add hrchat https://charts.internal/hrchat && helm repo update
# 离线环境（私有化offline bundle）
tar -zxmf hrchat-offline-1.0.x.tar.gz -C /opt/hrchat
cd /opt/hrchat
helm repo add hrchat file:///opt/hrchat/charts   # 本地repo
# 离线镜像导入（各Worker节点 或 直接推Harbor）
for img in $(cat images.txt); do docker load -i images/$img.tar; done
```

### 4.2 中间件部署（hrchat-middleware）

> 顺序：存储类（MySQL/PG/MinIO）→ Redis → Kafka。中间件Chart支持独立生命周期，与应用解耦。

```bash
# 1. 创建命名空间与密钥（⚠️生产密钥必须来自Vault/密码机，禁止明文入values）
kubectl create ns hrchat-middleware

# 2. MySQL（主从半同步）
helm install mysql hrchat/mysql -n hrchat-middleware \
  -f values/mysql-prod.yaml \
  --set auth.rootPassword=$(vault kv get -field=root_pwd secret/hrchat/mysql) \
  --set auth.replicationPassword=$(vault kv get -field=repl_pwd secret/hrchat/mysql)

# 3. PostgreSQL（含pgvector扩展）
helm install pg hrchat/postgresql -n hrchat-middleware -f values/pg-prod.yaml \
  --set auth.postgresPassword=$(vault kv get -field=pg_pwd secret/hrchat/pg)

# 4. Redis哨兵 / 5. Kafka / 6. MinIO（同模式，略——详见values/目录注释）
```

🔍 中间件验证：

```bash
kubectl get pods -n hrchat-middleware        # 全部Running 1/1
# MySQL主从
kubectl exec -n hrchat-middleware mysql-0 -- mysql -uroot -p$PWD \
  -e "SHOW REPLICA STATUS\G" | grep -E "Slave_IO_Running|Slave_SQL_Running"  # 双Yes
# pgvector
kubectl exec -n hrchat-middleware pg-0 -- psql -U hrchat -d hrchat_state \
  -c "CREATE EXTENSION IF NOT EXISTS vector; SELECT extversion FROM pg_extension WHERE extname='vector';"
```

### 4.3 数据库初始化

#### 4.3.1 MySQL业务库（Flyway自动迁移）

> Java应用启动时自动执行Flyway（DB文档§14）；首次部署仅创建库与账号：

```sql
-- 管理员执行（仅首次）
CREATE DATABASE hrchat_meta DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'hrchat_app'@'%' IDENTIFIED BY '<强密码>';
GRANT SELECT,INSERT,UPDATE,DELETE ON hrchat_meta.* TO 'hrchat_app'@'%';
-- ⚠️DDL权限仅给迁移专用账号，应用账号无DDL（最小权限）
CREATE USER 'hrchat_migrator'@'%' IDENTIFIED BY '<强密码>';
GRANT ALL ON hrchat_meta.* TO 'hrchat_migrator'@'%';
```

#### 4.3.2 Doris建库与账号

```sql
-- 通过FE 9030端口MySQL客户端执行
CREATE DATABASE hrchat_dwd;
-- ⚠️应用只读账号（BR-01）
CREATE USER 'hrchat_query'@'%' IDENTIFIED BY '<强密码>';
GRANT SELECT_PRIV ON hrchat_dwd.* TO 'hrchat_query'@'%';
```

#### 4.3.3 数仓表结构初始化

```bash
# Doris DDL经Flyway自定义执行器随应用发布执行；或手动：
mysql -h <fe-vip> -P9030 -uadmin -p < sql/doris/V1.0_001__init_dims.sql
mysql -h <fe-vip> -P9030 -uadmin -p < sql/doris/V1.0_002__init_facts.sql
# 动态分区配置（事实表月分区自动滚动）
mysql -h <fe-vip> -P9030 -uadmin -p < sql/doris/V1.0_003__dynamic_partition.sql
```

#### 4.3.4 种子数据

```bash
# 角色预置/13个核心指标/默认脱敏策略/初始同义词（DB文档§14.4）
mysql -h <mysql-svc> -u hrchat_migrator -p hrchat_meta < sql/seed/V1.0_010__seed.sql
```

#### 4.3.5 源系统接入与首次全量同步

```bash
# 1. 录入数据源（凭证写Vault，DB存引用）
kubectl apply -f integration/datasources.yaml   # 含各源系统连接引用
# 2. 手动触发首次全量（DataX任务）
kubectl create job --from=cronjob/datax-hris-full datax-hris-first -n hrchat-integration
# 3. 观察（首次约2-4小时，视数据量）
kubectl logs -f job/datax-hris-first -n hrchat-integration
```

🔍 首灌验证：

```sql
-- 与源系统抽样核对（如在职人数）
SELECT COUNT(*) FROM hrchat_dwd.dim_employee WHERE is_current=1 AND emp_status=1;
-- 质量校验结果
SELECT task_type,exec_state,rows_written,fail_reason
FROM hrchat_meta.itg_sync_task ORDER BY id DESC LIMIT 10;
```

### 4.4 应用部署流程

#### 4.4.1 配置渲染

```bash
# 生成环境专属values（模板 + 环境参数）
cp values/env.example.yaml values/prod.yaml
vi values/prod.yaml    # 填写§5环境变量清单中的配置项

# 敏感配置注入（⚠️禁止写入values文件提交Git）
kubectl create secret generic hrchat-secrets -n hrchat-app \
  --from-literal=DB_PASSWORD=$(vault kv get -field=db_pwd secret/hrchat/app) \
  --from-literal=REDIS_PASSWORD=$(...) \
  --from-literal=OPENAI_COMPAT_API_KEY=$(...) \
  --from-literal=MINIO_SECRET_KEY=$(...) \
  --from-literal=OIDC_CLIENT_SECRET=$(...)
```

#### 4.4.2 按依赖顺序部署

```bash
# 1. vLLM模型服务（模型加载3-8分钟，先启动）
helm install vllm hrchat/hrchat-vllm -n hrchat-gpu -f values/vllm-prod.yaml
kubectl wait --for=condition=ready pod -l app=vllm-qwen72b \
  -n hrchat-gpu --timeout=900s
# 🔍验证推理
curl http://vllm-qwen72b.hrchat-gpu:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen2.5-72b-awq","messages":[{"role":"user","content":"你好"}],"max_tokens":16}'

# 2. 可观测组件（先于应用，便于采集启动日志）
helm install observe hrchat/hrchat-observe -n hrchat-observe -f values/observe.yaml

# 3. AI运行时
helm install ai hrchat/hrchat-ai-runtime -n hrchat-ai -f values/ai-prod.yaml
kubectl rollout status deploy/agent-gateway -n hrchat-ai --timeout=300s

# 4. Java应用（含Flyway迁移，自动执行DB初始化与种子）
helm install server hrchat/hrchat-server -n hrchat-app -f values/server-prod.yaml
kubectl rollout status deploy/java-app -n hrchat-app --timeout=600s

# 5. 前端
helm install web hrchat/hrchat-web -n hrchat-app -f values/web-prod.yaml

# 6. 网关（最后，切换流量入口）
helm install gateway hrchat/hrchat-gateway -f values/gateway-prod.yaml

# 7. 数据集成常驻任务（Flink CDC/XXL-Job）
helm install integration hrchat/hrchat-integration -n hrchat-integration -f values/integration.yaml
```

#### 4.4.3 部署后资源确认

```bash
kubectl get pods -A | grep hrchat    # 🔍全部Running，RESTARTS无异常增长
kubectl get hpa -A | grep hrchat     # 🔍HPA就绪（chat副本按CPU 60%伸缩）
```

---

## 5. 环境变量配置说明

> 配置三级优先级：环境变量 > values.yaml > 应用默认值。**敏感项一律走Secret注入**（⚠️）。

### 5.1 Java应用（java-app）

| 变量 | 必填 | 示例/默认 | 说明 |
|---|---|---|---|
| SPRING_PROFILES_ACTIVE | 是 | prod | prod含严格校验 |
| DB_URL | 是 | jdbc:mysql://mysql.hrchat-middleware:3306/hrchat_meta | 业务库 |
| DB_USERNAME / DB_PASSWORD | 是 | hrchat_app / Secret | 应用账号（无DDL） |
| DORIS_URL | 是 | jdbc:mysql://doris-fe.hrchat-olap:9030/hrchat_dwd | 只读查询 |
| DORIS_USERNAME / DORIS_PASSWORD | 是 | hrchat_query / Secret | 只读账号（BR-01） |
| REDIS_HOST / REDIS_PASSWORD | 是 | redis-sentinel… / Secret | 哨兵模式含MASTER_NAME |
| KAFKA_BOOTSTRAP | 是 | kafka:9092 | 订阅/审计事件 |
| OIDC_ISSUER / OIDC_CLIENT_ID / OIDC_CLIENT_SECRET | 是 | https://sso.example.com / … / Secret | 认证（ARCH V-07） |
| AI_GATEWAY_URL | 是 | http://agent-gateway.hrchat-ai:8080 | AI运行时地址 |
| AUTHZ_CACHE_TTL_SECONDS | 否 | 300 | 权限指纹TTL（BR-12） |
| EXPORT_MAX_ROWS | 否 | 5000 | 导出行数上限（BR-06） |
| AUDIT_FLUSH_BATCH | 否 | 500 | 审计批量写入阈值 |
| LOG_LEVEL | 否 | INFO | 生产禁DEBUG |

### 5.2 AI运行时（agent-gateway / langgraph / agentscope）

| 变量 | 必填 | 示例/默认 | 说明 |
|---|---|---|---|
| OPENAI_COMPAT_BASE_URL | 是 | http://vllm-qwen72b.hrchat-gpu:8000/v1 | 主模型端点 |
| OPENAI_COMPAT_API_KEY | 是 | Secret | vLLM访问令牌 |
| MODEL_NAME_MAIN / MODEL_NAME_LITE | 是 | qwen2.5-72b-awq / qwen2.5-14b | 分级路由（TECH 4.3.1） |
| MODEL_FALLBACK_URL | 否 | https://api.internal-llm…/v1 | 云端兜底（仅Schema场景） |
| PG_CHECKPOINT_URL | 是 | postgresql://…/hrchat_state | LangGraph检查点 |
| VECTOR_DSN | 是 | pg同库 | pgvector |
| MCP_JAVA_URL | 是 | http://mcp-server.hrchat-app:9090 | 取数唯一入口（红线） |
| AGENT_MAX_CONCURRENT | 否 | 20 | 归因任务并发上限 |
| AGENT_BUDGET_DEFAULT_LLM / TOOLS | 否 | 20 / 30 | 预算默认值（BR-16） |
| SANDBOX_IMAGE | 否 | hrchat/sandbox:1.0 | Docker沙箱镜像 |
| LANGFUSE_HOST / LANGFUSE_KEY | 是 | http://langfuse… / Secret | Agent轨迹 |
| SENTRY_DSN | 否 | — | 异常上报 |

### 5.3 vLLM

| 变量 | 说明 |
|---|---|
| MODEL_PATH | 模型权重PVC挂载路径 |
| QUANTIZATION | awq（72B必开） |
| GPU_MEMORY_UTILIZATION | 0.92（独占GPU节点） |
| MAX_MODEL_LEN | 16384（上下文上限，成本权衡） |
| API_TOKEN | ⚠️必设（防内网未授权调用） |

### 5.4 网关与前端

| 变量 | 说明 |
|---|---|
| APISIX_UPSTREAM_WEB / SERVER / AI | 三上游路由 |
| RATE_LIMIT_QPS | 默认100（全局），单用户10 |
| SSE_TIMEOUT | 300s（防断流） |
| CORS_ORIGIN | 前端域名白名单 |

---

## 6. 安全配置指南

### 6.1 部署期安全基线（⚠️逐项必查）

| # | 项 | 操作 | 验证 |
|---|---|---|---|
| 1 | Secret管理 | 全部敏感项Secret注入，values文件不含明文 | `grep -ri password values/` 无结果 |
| 2 | 镜像安全 | 镜像签名验证；非root运行（runAsNonRoot） | `kubectl get pod -o yaml \| grep runAsUser` 非0 |
| 3 | Pod安全标准 | 命名空间打标签 `pod-security.kubernetes.io/enforce=restricted` | ns label检查 |
| 4 | NetworkPolicy | ai命名空间默认拒绝出网已生效 | 从ai pod `curl 外网` 应失败 |
| 5 | vLLM鉴权 | API_TOKEN已设置 | 无Token调用应401 |
| 6 | Doris只读 | hrchat_query账号仅SELECT_PRIV | `SHOW GRANTS FOR hrchat_query` |
| 7 | 最小权限RBAC | 应用ServiceAccount无cluster-admin | `kubectl auth can-i --as=system:serviceaccount:hrchat-app:java-app` |
| 8 | 传输加密 | 全链路TLS（入口证书+网格内mTLS可选） | `curl -I https://域名` 证书有效 |
| 9 | 审计就绪 | DDL/DCL审计插件开启（MySQL audit_log） | 审计文件生成 |
| 10 | 水印 | 导出文件水印开关开启 | 试导出含"工号+时间"水印 |

### 6.2 密钥轮换

| 密钥 | 轮换周期 | 方式 |
|---|---|---|
| DB/Redis/MinIO密码 | 季度 | Vault动态密钥→滚动重启（选择低峰） |
| OIDC Client Secret | 半年 | IAM侧重置→Secret更新→滚动重启 |
| vLLM API_TOKEN | 半年 | 同步更新ai运行时Secret |
| TLS证书 | 自动 | cert-manager 30天前自动续期 |

### 6.3 渗透验证（上线前）

按PRD 9.4执行：越权用例100%拦截、SQL注入全拦截、无高危漏洞（Trivy镜像扫描+人工渗透）。

---

## 7. 监控告警设置

### 7.1 监控栈自动部署

§4.4.2第2步已随Chart部署：Prometheus(HA)+Grafana+Alertmanager+SkyWalking+ELK+Langfuse。

### 7.2 预置监控看板（Grafana导入JSON，Chart含仪表盘ConfigMap）

| 看板 | 核心面板 | 数据源 |
|---|---|---|
| 业务总览 | 问数QPS/P95延迟/问数成功率/降级状态 | Prometheus+SkyWalking |
| AI链路 | LLM延迟分布/Token消耗/缓存命中率/归因任务收敛率 | Prometheus+Langfuse |
| 模型服务 | vLLM吞吐/队列长度/GPU利用率显存 | vLLD metrics |
| 数据链路 | 同步任务状态/延迟/质量校验失败数 | Prometheus+MySQL |
| 中间件 | MySQL主从延迟/Redis命中率/Kafka积压/Doris查询P95 | 各exporter |

### 7.3 告警规则（Alertmanager，关键项预置）

| 告警名 | 条件 | 级别 | 通知 |
|---|---|---|---|
| ServiceUnavailable | 5xx比率>1%持续2min | P0 | 电话+IM |
| AskLatencyHigh | 问数P95>3s持续5min | P1 | IM |
| ModelServiceDown | vLLM健康检查失败 | P0 | 电话+IM |
| DataSyncFailed | 同步任务失败（重试3次后） | P1 | IM（数据管理员） |
| DataSyncDelayed | 06:30未完成T+1 | P1 | IM |
| KafkaLagHigh | 消费积压>10000 | P2 | IM |
| MySQLReplicationBroken | 主从中断 | P0 | 电话 |
| TokenBudgetAlert | 日Token消耗>80%配额 | P2 | IM（成本管理） |
| PermissionDeniedSpike | 越权拦截次数环比+300% | P0 | 电话（疑似攻击/泄露） |

> 每条告警关联Runbook链接（RB-01~06，ARCH V-11），Alertmanager注解中携带。

### 7.4 日志采集

- Filebeat DaemonSet采集容器stdout → ES，Index按 `hrchat-{namespace}-%{+yyyy.MM.dd}`
- 结构化JSON日志统一字段：`trace_id`（关联SkyWalking/Langfuse）、`user_no`（⚠️日志脱敏器先过滤L3字段再入ES）
- 审计日志独立Index `hrchat-audit-*`，保留策略3年（ILM热30天→温11月→冷）

---

## 8. 部署验证步骤

### 8.1 冒烟测试（Smoke Test，部署完成后30分钟内执行）

```bash
# ===== 1. 入口与证书 =====
curl -sI https://hrchat.example.com | head -3        # 🔍200/301，证书正常

# ===== 2. 登录链路（OIDC） =====
# 浏览器访问→跳转SSO→登录回跳→获取JWT（人工步骤，录入检查表）

# ===== 3. 健康检查端点 =====
curl -s https://hrchat.example.com/api/v1/health | jq
# 🔍期望：{"status":"UP","components":{"db":"UP","redis":"UP","doris":"UP","ai":"UP","vllm":"UP"}}

# ===== 4. 核心功能冒烟（用测试账号） =====
# 4.1 简单问数（经API直调，绕过前端验证后端全链路）
TOKEN="<测试账号JWT>"
curl -N -X POST https://hrchat.example.com/api/v1/chat/sessions/test-smoke/ask \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"当前在职人数是多少？"}'
# 🔍期望SSE流：MESSAGE_DELTA... → ANSWER_DONE，结论含数字与口径说明

# 4.2 权限验证（用受限账号问薪酬）
# 🔍期望：无权限态话术，非数据返回

# 4.3 降级验证
kubectl scale deploy/vllm-qwen72b -n hrchat-gpu --replicas=0
# 重发问数 → 🔍预期：Top200模板问句返回+降级提示条（FR-24）
kubectl scale deploy/vllm-qwen72b -n hrchat-gpu --replicas=1   # 恢复

# ===== 5. 数据链路 =====
# 检查最近一夜同步（若已过调度时间）
curl -s https://hrchat.example.com/api/v1/admin/sync/status -H "Authorization: Bearer $ADMIN_TOKEN" | jq
# 🔍全部数据源 exec_state=2(成功) 或按计划待执行

# ===== 6. 审计落库 =====
mysql -h<mysql> -u hrchat_app -p -e \
 "SELECT event_type,COUNT(*) FROM hrchat_meta.aud_audit_log GROUP BY event_type;"
# 🔍冒烟产生的QUESTION/SQL_EXEC事件已记录
```

### 8.2 验收检查表（📦归档）

| 类别 | 项 | 结果 |
|---|---|---|
| 入口 | HTTPS/证书/域名 | ☐ |
| 认证 | SSO登录/登出/会话过期 | ☐ |
| 问数 | 简单查询/澄清/多轮 | ☐ |
| 权限 | 行级拦截/字段脱敏/导出水印 | ☐ |
| 降级 | vLLM停止→模板直查+提示 | ☐ |
| 数据 | 首灌核对/同步监控页 | ☐ |
| 监控 | 看板有数据/告警测试触发 | ☐ |
| 审计 | 冒烟操作留痕 | ☐ |
| 准确率 | 500问句集回归≥90%（算法侧执行） | ☐ |

全部通过 → 部署完成，进入UAT；任一失败 → §9排查或§10回滚。

---

## 9. 常见问题排查与解决方案

### 9.1 排查通用三板斧

```bash
kubectl describe pod <pod> -n <ns>      # 事件（调度/镜像/探针失败）
kubectl logs <pod> -n <ns> --previous   # 上次崩溃日志
kubectl exec -it <pod> -n <ns> -- sh    # 进容器网络连通性验证
```

### 9.2 问题速查表

| 现象 | 可能原因 | 解决 |
|---|---|---|
| Pod Pending | GPU不足/资源不足/PVC未绑定 | describe看事件；GPU节点检查device-plugin；扩容 |
| vLLM启动OOM | GPU显存不足/权重未量化 | 确认awq镜像；GPU_MEMORY_UTILIZATION降至0.85；确认独占节点 |
| vLLM加载慢/失败 | 权重PVC网络盘IO低 | 模型预置到本地盘镜像或hostPath |
| Java启动失败：Flyway报错 | 迁移脚本校验失败/账号无DDL | 核对migrator账号；`flyway repair`（脚本checksum冲突时） |
| 健康检查db DOWN | MySQL密码/网络策略 | Secret与Vault核对；NetworkPolicy放行3306 |
| 问数返回"解析失败" | vLLM未就绪/模型路由错 | health检查；ai运行时日志查ModelAdapter；降级开关是否生效 |
| SSE无输出即断 | 网关缓冲/超时 | APISIX关闭响应缓冲，SSE_TIMEOUT调大；K8s Ingress注解`proxy-read-timeout` |
| 问数慢（>3s） | 见9.3分步定位 | — |
| 权限5分钟未生效 | Redis失效广播失败/多副本不同步 | 检查Kafka topic；`DEL authz:ver:<user>`；确认副本连同一Redis |
| Doris查询超时 | BE负载/无分区裁剪 | SHOW QUERY PROFILE；确认查询带dt/org过滤；BE扩容 |
| 同步失败：账号拒绝 | 源系统凭证过期 | Vault更新凭证→重跑任务（itg_sync_task重试按钮） |
| 数据为空但同步成功 | 口径过滤（如status）/CDC断流 | 核对事实表行数；Flink CDC checkpoint位置 |
| 审计日志缺失 | Kafka积压/消费者挂 | KafkaLag告警；重启audit消费者；补投机制检查 |
| 前端白屏 | 静态资源404/路由base | 网关web upstream配置；浏览器控制台 |
| 归因任务全部超时 | 沙箱池未就绪/预算过紧 | sandbox-pool Pod状态；调AGENT_BUDGET |

### 9.3 问数慢分步定位（SOP）

```
1. Grafana「业务总览」看P95分布定位段：
   路由段慢(>500ms) → 查14B服务负载/queue → 扩vllm-14b
   NL2SQL段慢(>1.2s) → Langfuse看token长度 → Prompt裁剪/few-shot瘦身
   查询段慢(>800ms) → Doris PROFILE → 物化视图/Colocate检查
   全段慢但缓存命中低 → 检查perm_fp键生成（权限频繁变化会击穿缓存）
```

---

## 10. 回滚机制

### 10.1 回滚决策矩阵

| 场景 | 回滚对象 | 方式 | RTO |
|---|---|---|---|
| 应用缺陷（新版本Bug） | java-app/ai-runtime/web | Helm revision回退（§10.2） | ≤10min |
| 数据库迁移缺陷 | Flyway schema | 前向修复（Forward-fix，§10.4） | 视变更 |
| 配置错误 | values/Secret | helm rollback或热更新 | ≤5min |
| 中间件故障 | 非回滚场景 | 故障转移（RB-03），见V-10 | RTO表 |
| 全站不可用 | — | 降级梯度L3（只读报表） | 即时 |

**原则：数据库不回滚（rollback schema风险高于前向修复），应用与配置永远可回滚。**

### 10.2 应用回滚操作

```bash
# 查看历史版本
helm history server -n hrchat-app
# 回退到上一版本
helm rollback server <revision> -n hrchat-app --wait --timeout 600s
# 多组件需按序回退（保持接口契约兼容窗口内的版本）：
# ai依赖mcp接口 → 先回server再回ai；前端最后
```

**兼容性规则**：Chart维护**接口契约兼容窗口**（前一版本），窗口内应用可独立回滚；跨窗口回滚需同步回滚关联组件（版本矩阵见附录12.2）。

### 10.3 回滚验证

```bash
kubectl rollout status deploy/java-app -n hrchat-app
curl -s https://hrchat.example.com/api/v1/health | jq .status   # UP
# 冒烟：§8.1第4步问数冒烟重跑
```

### 10.4 数据库迁移回滚策略

- Flyway脚本**只进不改**（DB文档§14.1）；错误迁移以**新补偿脚本**修复（如误加列→新版本脚本drop）
- 破坏性变更（L3级）发布前必须已通过staging全量回放，且附带**应急补偿脚本**存档于发布单

---

## 11. 版本更新流程

### 11.1 标准发布流程（例行版本）

```
1. 发布申请：版本号/变更清单/影响面/回滚预案 → 审批
2. 制品就绪：镜像推送Harbor（签名）→ Chart版本 bump
3. Staging验证：自动部署+全量回归（500问句集≥90%门禁）+ UAT抽检
4. 发布窗口：工作日 10:00-16:00（避开月末结算冻结期，ARCH §14.6）
5. 生产执行：
   helm repo update
   helm upgrade server hrchat/hrchat-server -n hrchat-app \
     -f values/prod.yaml --version 1.0.<n> --wait --timeout 900s
   # 组件顺序：server(含Flyway) → ai → web → gateway路由切换
6. 金丝雀（AI运行时变化时）：先5%流量灰度（APISIX权重路由）
   观察P95/准确率/报错30分钟 → 50% → 100%
7. 发布后观察：值班盯守2小时看板 → 发布确认单归档
```

### 11.2 发布检查门禁

| 门禁 | 不通过则 |
|---|---|
| CI：单测/ArchUnit/契约测试/Trivy扫描（无高危） | 禁止出包 |
| Staging问句集准确率≥90% | 禁止生产发布 |
| 兼容窗口核对（跨组件版本矩阵） | 需捆绑发布方案 |
| 应急补偿脚本就绪（含L3 DB变更时） | 审批否决 |

### 11.3 紧急发布（Hotfix）

- 走加急审批（口头+事后24h补单）；跳过窗口限制但保留staging冒烟
- Hotfix分支从生产tag拉出，禁止带未发布功能

### 11.4 版本命名与停服策略

- 版本：语义化 `主.次.修订`（1.0.x）；Chart与镜像tag一致
- 停服窗口：应用滚动发布**不停服**；Doris FE滚动重启秒级闪断（选低峰）；MySQL主从切换≤60s
- 版本退役：旧镜像保留最近10个tag；Chart版本保留最近20个revision

---

## 12. 附录

### 12.1 部署文件清单

```
hrchat-deploy/
├── charts/                    # Helm Charts（web/server/ai/vllm/observe/integration/middleware×6）
├── values/
│   ├── env.example.yaml       # 环境配置模板（注释齐全）
│   ├── prod.yaml / staging.yaml
│   └── vllm-prod.yaml 等      # 组件级配置
├── sql/
│   ├── mysql/                 # Flyway脚本（随应用执行）
│   ├── doris/                 # 数仓DDL
│   └── seed/                  # 种子数据
├── integration/datasources.yaml
├── observability/             # 告警规则+Grafana看板JSON+Runbook链接
└── docs/checklist-deploy.xlsx # 部署检查表模板
```

### 12.2 组件版本兼容矩阵（示例，随发布维护）

| server | 兼容 ai-runtime | 兼容 mcp契约 | 兼容 DB schema | 说明 |
|---|---|---|---|---|
| 1.0.3 | 1.0.2 / 1.0.3 | v1 | V1.0_0xx | 当前生产 |
| 1.0.4 | 1.0.3 / 1.0.4 | v1 | V1.0_0xx(+V1.0_045) | 下一版本 |

### 12.3 关键运维命令速查

```bash
# 全栈状态一览
kubectl get pods -A | grep hrchat | grep -v Running
# 重启某应用（滚动）
kubectl rollout restart deploy/java-app -n hrchat-app
# 进入Doris管理
mysql -h doris-fe.hrchat-olap -P9030 -uadmin -p
# 手动触发订阅推送验证
kubectl create job --from=cronjob/subscription-daily sub-test -n hrchat-app
# 强制降级开关（L2）
kubectl set env deploy/agent-gateway -n hrchat-ai DEGRADE_LEVEL=2
```

### 12.4 联系与升级支持

| 事项 | 联系 |
|---|---|
| 部署问题 | SRE值班群 / Runbook RB-01~06 |
| 模型/GPU问题 | 算法组值班 |
| 源系统接入 | 各源系统团队接口人（见检查表3.1） |
| 安全事件 | 安全应急热线（P0级） |
```
