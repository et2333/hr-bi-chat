-- =====================================================================
-- HR智能问数 MySQL 业务库 hrchat_meta 建表脚本（生产版，DB-HRCHATBI-001）
-- Flyway MySQL V1 baseline 的部署侧快照；后续变更只新增版本迁移，不回改已发布 baseline。
-- 通用列：id/created_at/created_by/updated_at/updated_by/is_deleted
-- =====================================================================
CREATE DATABASE IF NOT EXISTS hrchat_meta DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE hrchat_meta;

-- ---------------- 多租户域 t_ ----------------
CREATE TABLE IF NOT EXISTS t_tenant (
  id                 BIGINT UNSIGNED AUTO_INCREMENT,
  tenant_code        VARCHAR(16) NOT NULL COMMENT '租户编码（唯一）',
  tenant_name        VARCHAR(64) NOT NULL COMMENT '租户名称',
  status             TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  user_quota         INT NOT NULL DEFAULT 500 COMMENT '用户数配额',
  report_quota       INT NOT NULL DEFAULT 200 COMMENT '报表数配额',
  subscription_quota INT NOT NULL DEFAULT 50 COMMENT '订阅数配额',
  api_daily_quota    INT NOT NULL DEFAULT 10000 COMMENT 'API 日调用配额',
  created_at         DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by         VARCHAR(64) DEFAULT NULL,
  updated_at         DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by         VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE=InnoDB COMMENT='租户表';

-- ---------------- 权限安全域 sec_ ----------------
CREATE TABLE IF NOT EXISTS sec_user (
  id            BIGINT UNSIGNED AUTO_INCREMENT,
  emp_no        VARCHAR(32)  NOT NULL COMMENT '工号，SSO唯一标识',
  display_name  VARCHAR(64)  NOT NULL COMMENT '姓名（镜像自HRIS）',
  email         VARCHAR(128) DEFAULT NULL COMMENT '企业邮箱',
  org_node_id   BIGINT UNSIGNED NOT NULL COMMENT '主属组织，FK→sec_org_node.id',
  status        TINYINT NOT NULL DEFAULT 1 COMMENT '0离职 1在职 2停用',
  last_login_at DATETIME(3) DEFAULT NULL,
  prefs_json    JSON DEFAULT NULL COMMENT '用户偏好',
  tenant_id     VARCHAR(16) DEFAULT 't01' COMMENT '所属租户',
  password_hash VARCHAR(128) DEFAULT NULL COMMENT '本地演示密码哈希（SSO 对接后废弃）',
  must_change_pwd TINYINT(1) DEFAULT 0 COMMENT '首次登录须改密',
  is_deleted    TINYINT(1) NOT NULL DEFAULT 0,
  created_at    DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(64) DEFAULT NULL,
  updated_at    DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by    VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_no (emp_no),
  KEY idx_org_node (org_node_id),
  KEY idx_status (status)
) ENGINE=InnoDB COMMENT='用户表(SSO同步，不含密码——认证委托IAM)';

CREATE TABLE IF NOT EXISTS sec_org_node (
  id           BIGINT UNSIGNED AUTO_INCREMENT,
  org_code     VARCHAR(64)  NOT NULL COMMENT '组织编码(源HRIS)',
  org_name     VARCHAR(128) NOT NULL,
  parent_id    BIGINT UNSIGNED DEFAULT 0 COMMENT '父节点,0=根',
  org_path     VARCHAR(1024) NOT NULL COMMENT '物化路径:/1/35/102/ 权限过滤核心列',
  org_level    TINYINT NOT NULL COMMENT '1集团 2公司 3一级部门…',
  status       TINYINT NOT NULL DEFAULT 1 COMMENT '0撤销 1生效',
  hr_effect_at DATETIME(3) DEFAULT NULL COMMENT 'HRIS生效时间',
  tenant_id    VARCHAR(16) DEFAULT 't01' COMMENT '所属租户',
  is_deleted   TINYINT(1) NOT NULL DEFAULT 0,
  created_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by   VARCHAR(64) DEFAULT NULL,
  updated_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by   VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_parent (parent_id),
  KEY idx_org_path (org_path(255))
) ENGINE=InnoDB COMMENT='组织镜像(SyncTask维护,口径以HRIS为准,BR-14)';

CREATE TABLE IF NOT EXISTS sec_role (
  id       BIGINT UNSIGNED AUTO_INCREMENT,
  role_code VARCHAR(64) NOT NULL COMMENT 'HRBP/HRD/CHO/PAYROLL/ADMIN/TENANT_ADMIN/DATA_ADMIN',
  role_name VARCHAR(64) NOT NULL,
  data_level TINYINT NOT NULL DEFAULT 1 COMMENT '数据层级:1明细受限 2部门汇总 3全局汇总',
  PRIMARY KEY (id), UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB COMMENT='角色(功能权限RBAC载体)';

CREATE TABLE IF NOT EXISTS sec_user_role (
  id      BIGINT UNSIGNED AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  role_code VARCHAR(64) NOT NULL,
  granted_by VARCHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_code),
  KEY idx_role (role_code)
) ENGINE=InnoDB COMMENT='用户-角色(多对多)';

CREATE TABLE IF NOT EXISTS sec_org_grant (
  id            BIGINT UNSIGNED AUTO_INCREMENT,
  grantee_type  TINYINT NOT NULL COMMENT '1用户 2用户组',
  grantee_id    VARCHAR(64) NOT NULL COMMENT '用户工号或组编码',
  org_node_id   BIGINT UNSIGNED NOT NULL COMMENT '授权组织节点(含全部下级)',
  grant_scope   TINYINT NOT NULL DEFAULT 1 COMMENT '1查询 2查询+明细 3查询+明细+导出',
  effective_at  DATETIME(3) NOT NULL,
  expire_at     DATETIME(3) DEFAULT NULL COMMENT 'NULL=长期',
  source_type   TINYINT NOT NULL DEFAULT 1 COMMENT '1人工 2HRIS异动 3审批通过',
  PRIMARY KEY (id),
  UNIQUE KEY uk_grant (grantee_type, grantee_id, org_node_id),
  KEY idx_grantee (grantee_type, grantee_id, expire_at),
  KEY idx_org (org_node_id)
) ENGINE=InnoDB COMMENT='组织范围授权(变更≤5min生效BR-12)';

CREATE TABLE IF NOT EXISTS sec_field_policy (
  id           BIGINT UNSIGNED AUTO_INCREMENT,
  field_code   VARCHAR(128) NOT NULL COMMENT 'salary.gross_pay/id_card/mobile',
  domain       VARCHAR(32) NOT NULL COMMENT 'payroll/personal/performance',
  policy_type  TINYINT NOT NULL COMMENT '1隐藏 2脱敏 3汇总可见 4明文(需审批)',
  role_code    VARCHAR(64) NOT NULL COMMENT '适用角色',
  min_group_size INT DEFAULT NULL COMMENT '汇总显示最小聚合人数',
  approval_required TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_field_role (field_code, role_code),
  KEY idx_domain (domain)
) ENGINE=InnoDB COMMENT='字段级策略(D-3裁决表数据载体)';

-- ---------------- 语义层域 biz_ ----------------
CREATE TABLE IF NOT EXISTS biz_metric (
  id            BIGINT UNSIGNED AUTO_INCREMENT,
  metric_code   VARCHAR(64)  NOT NULL,
  metric_name   VARCHAR(128) NOT NULL,
  domain        VARCHAR(32)  NOT NULL COMMENT 'staff/org/recruit/attendance/pay/perf',
  formula_expr  VARCHAR(1024) NOT NULL COMMENT '计算公式表达式',
  calc_scope    VARCHAR(512) NOT NULL COMMENT '口径文字说明',
  default_period VARCHAR(16) DEFAULT 'MONTH',
  good_direction TINYINT NOT NULL DEFAULT 1 COMMENT '优劣方向:1越高越好',
  perm_level     TINYINT NOT NULL DEFAULT 1 COMMENT '数据密级:1公开~3敏感(向量库perm_level同步)',
  status        TINYINT NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
  is_deleted    TINYINT(1) NOT NULL DEFAULT 0,
  created_at    DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(64) DEFAULT NULL,
  updated_at    DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by    VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_metric_code (metric_code),
  KEY idx_domain_status (domain, status)
) ENGINE=InnoDB COMMENT='指标定义(口径唯一由版本表保证BR-04)';

CREATE TABLE IF NOT EXISTS biz_metric_version (
  id           BIGINT UNSIGNED AUTO_INCREMENT,
  metric_id    BIGINT UNSIGNED NOT NULL,
  version_no   INT NOT NULL COMMENT '版本号,同一指标自增',
  formula_expr VARCHAR(1024) NOT NULL,
  calc_scope   VARCHAR(512) NOT NULL,
  status       TINYINT NOT NULL DEFAULT 0 COMMENT '0待审批 1生效中 2已驳回 3历史',
  submitted_by VARCHAR(64) NOT NULL,
  approved_by  VARCHAR(64) DEFAULT NULL,
  effective_at DATETIME(3) DEFAULT NULL,
  change_note  VARCHAR(512) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_metric_version (metric_id, version_no),
  KEY idx_status (status)
) ENGINE=InnoDB COMMENT='指标版本(仅一条status=1由应用事务保证)';

CREATE TABLE IF NOT EXISTS biz_dimension (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  dim_code    VARCHAR(64) NOT NULL COMMENT 'org/time/job_level/job_family/tenure_band',
  dim_name    VARCHAR(64) NOT NULL,
  dim_type    TINYINT NOT NULL COMMENT '1结构 2枚举 3区间',
  ref_table   VARCHAR(128) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_dim_code (dim_code)
) ENGINE=InnoDB COMMENT='维度定义';

CREATE TABLE IF NOT EXISTS biz_dim_value (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  dim_id      BIGINT UNSIGNED NOT NULL,
  value_code  VARCHAR(64) NOT NULL,
  value_label VARCHAR(128) NOT NULL,
  sort_no     INT NOT NULL DEFAULT 0,
  parent_code VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_dim_value (dim_id, value_code)
) ENGINE=InnoDB COMMENT='维度枚举值';

CREATE TABLE IF NOT EXISTS biz_metric_dim (
  id        BIGINT UNSIGNED AUTO_INCREMENT,
  metric_id BIGINT UNSIGNED NOT NULL,
  dim_id    BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_md (metric_id, dim_id)
) ENGINE=InnoDB COMMENT='指标可用维度(N:N)';

CREATE TABLE IF NOT EXISTS biz_synonym (
  id         BIGINT UNSIGNED AUTO_INCREMENT,
  term_group VARCHAR(128) NOT NULL COMMENT '同义词组',
  target_type TINYINT NOT NULL COMMENT '1指标 2维度 3报表模板 4枚举值',
  target_id  BIGINT UNSIGNED NOT NULL,
  hit_count  BIGINT NOT NULL DEFAULT 0,
  status     TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  KEY idx_term (term_group),
  KEY idx_target (target_type, target_id)
) ENGINE=InnoDB COMMENT='同义词库(NL归一化,FR-22)';

-- ---------------- 会话域 cht_ ----------------
CREATE TABLE IF NOT EXISTS cht_session (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  title       VARCHAR(128) NOT NULL COMMENT '会话标题(首问句截断18字)',
  status      TINYINT NOT NULL DEFAULT 1 COMMENT '1活跃 2上下文过期 3归档',
  last_active_at DATETIME(3) NOT NULL,
  is_pinned   TINYINT(1) NOT NULL DEFAULT 0,
  tenant_id   VARCHAR(16) DEFAULT 't01' COMMENT '所属租户',
  is_deleted  TINYINT(1) NOT NULL DEFAULT 0,
  created_at  DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(64) DEFAULT NULL,
  updated_at  DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_user_status (user_id, status, last_active_at)
) ENGINE=InnoDB COMMENT='会话(上下文热数据在Redis,此表为持久层)';

CREATE TABLE IF NOT EXISTS cht_turn (
  id            BIGINT UNSIGNED AUTO_INCREMENT,
  session_id    BIGINT UNSIGNED NOT NULL,
  turn_seq      INT NOT NULL,
  question_text VARCHAR(1024) NOT NULL,
  intent_type   TINYINT DEFAULT NULL COMMENT '1查询 2分析 3操作 4归因 5闲聊',
  inherit_json  JSON DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_seq (session_id, turn_seq),
  KEY idx_session (session_id)
) ENGINE=InnoDB COMMENT='问答轮次';

CREATE TABLE IF NOT EXISTS cht_answer (
  id             BIGINT UNSIGNED AUTO_INCREMENT,
  turn_id        BIGINT UNSIGNED NOT NULL,
  answer_state   TINYINT NOT NULL COMMENT '1完成 2无权限 3失败 4异步 5降级',
  summary_text   VARCHAR(1024) DEFAULT NULL,
  result_ref     VARCHAR(128) DEFAULT NULL,
  total_rows     INT NOT NULL DEFAULT 0,
  chart_type     VARCHAR(32) DEFAULT NULL,
  metric_ids     VARCHAR(255) DEFAULT NULL,
  metric_versions VARCHAR(128) DEFAULT NULL COMMENT '口径版本留痕(写时冻结,BR-09)',
  data_fresh_at  DATETIME(3) DEFAULT NULL,
  latency_ms     INT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_turn (turn_id),
  KEY idx_state (answer_state)
) ENGINE=InnoDB COMMENT='答案(明细不落库,大结果走MinIO)';

CREATE TABLE IF NOT EXISTS sys_idempotency_record (
  id               BIGINT UNSIGNED AUTO_INCREMENT,
  scope_key        CHAR(64) NOT NULL COMMENT '租户/用户/接口/资源/幂等键作用域摘要',
  tenant_id        VARCHAR(16) NOT NULL,
  user_id          BIGINT UNSIGNED NOT NULL,
  endpoint         VARCHAR(192) NOT NULL,
  resource_id      VARCHAR(128) NOT NULL,
  idempotency_key  VARCHAR(128) NOT NULL,
  request_hash     CHAR(64) NOT NULL,
  status           VARCHAR(24) NOT NULL COMMENT 'PROCESSING/COMPLETED/FAILED_FINAL/FAILED_RETRYABLE',
  result_ref       VARCHAR(128) DEFAULT NULL,
  response_code    VARCHAR(64) DEFAULT NULL,
  response_body    LONGTEXT DEFAULT NULL,
  attempt_count    INT NOT NULL DEFAULT 1,
  retry_after      DATETIME(3) DEFAULT NULL,
  expires_at       DATETIME(3) NOT NULL,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotency_scope (scope_key),
  KEY idx_idempotency_expire (expires_at)
) ENGINE=InnoDB COMMENT='创建类接口幂等事实记录';

CREATE TABLE IF NOT EXISTS cht_clarify (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  turn_id     BIGINT UNSIGNED NOT NULL,
  ambiguity_type TINYINT NOT NULL COMMENT '1时间缺失 2多口径 3组织歧义 4范围缺失',
  question_text VARCHAR(256) NOT NULL,
  options_json JSON NOT NULL COMMENT '候选选项(≤5,BR-07)',
  selected_code VARCHAR(64) DEFAULT NULL,
  saved_as_pref TINYINT(1) DEFAULT 0,
  PRIMARY KEY (id), KEY idx_turn (turn_id)
) ENGINE=InnoDB COMMENT='澄清记录';

CREATE TABLE IF NOT EXISTS cht_feedback (
  id        BIGINT UNSIGNED AUTO_INCREMENT,
  turn_id   BIGINT UNSIGNED NOT NULL,
  user_id   BIGINT UNSIGNED NOT NULL,
  rating    TINYINT NOT NULL COMMENT '1赞 -1踩',
  reason    TINYINT DEFAULT NULL COMMENT '1数据不对 2图表不对 3没听懂 4其他',
  comment   VARCHAR(512) DEFAULT NULL,
  handled   TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_turn_user (turn_id, user_id),
  KEY idx_handled (handled, created_at)
) ENGINE=InnoDB COMMENT='答案反馈(badcase回流,FR-06)';

-- ---------------- 报表域 rpt_ ----------------
CREATE TABLE IF NOT EXISTS rpt_report (
  id           BIGINT UNSIGNED AUTO_INCREMENT,
  report_name  VARCHAR(64) NOT NULL,
  owner_id     BIGINT UNSIGNED NOT NULL,
  source_type  TINYINT NOT NULL COMMENT '1问答转存 2模板实例 3拖拽搭建',
  template_id  BIGINT UNSIGNED DEFAULT NULL,
  params_json  JSON NOT NULL,
  status       TINYINT NOT NULL DEFAULT 1 COMMENT '0草稿 1已保存 2已删除',
  metric_versions VARCHAR(255) DEFAULT NULL,
  tenant_id   VARCHAR(16) DEFAULT 't01' COMMENT '所属租户',
  is_deleted   TINYINT(1) NOT NULL DEFAULT 0,
  created_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by   VARCHAR(64) DEFAULT NULL,
  updated_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by   VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_owner (owner_id, status),
  KEY idx_name (report_name)
) ENGINE=InnoDB COMMENT='报表聚合根';

CREATE TABLE IF NOT EXISTS rpt_component (
  id         BIGINT UNSIGNED AUTO_INCREMENT,
  report_id  BIGINT UNSIGNED NOT NULL,
  comp_type  TINYINT NOT NULL COMMENT '1图表 2明细表 3指标卡',
  chart_type VARCHAR(32) DEFAULT NULL,
  def_json   JSON NOT NULL COMMENT '组件定义(语义层对象,非SQL)',
  sort_no    INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_report (report_id)
) ENGINE=InnoDB COMMENT='报表组件';

CREATE TABLE IF NOT EXISTS rpt_subscription (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  report_id   BIGINT UNSIGNED NOT NULL,
  freq        TINYINT NOT NULL COMMENT '1日 2周 3月',
  channel     TINYINT NOT NULL COMMENT '1邮件 2企微 3钉钉 4飞书',
  next_run_at DATETIME(3) NOT NULL,
  status      TINYINT NOT NULL DEFAULT 1,
  perm_snapshot_json JSON DEFAULT NULL,
  tenant_id   VARCHAR(16) DEFAULT 't01' COMMENT '所属租户',
  is_deleted  TINYINT(1) NOT NULL DEFAULT 0,
  created_at  DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(64) DEFAULT NULL,
  updated_at  DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_next_run (status, next_run_at),
  KEY idx_report (report_id)
) ENGINE=InnoDB COMMENT='订阅(访问实时裁决BR-13)';

CREATE TABLE IF NOT EXISTS rpt_sub_receiver (
  id            BIGINT UNSIGNED AUTO_INCREMENT,
  subscription_id BIGINT UNSIGNED NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  push_status   TINYINT NOT NULL DEFAULT 0 COMMENT '0待推 1成功 2失败',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sub_user (subscription_id, user_id)
) ENGINE=InnoDB COMMENT='订阅收件人';

CREATE TABLE IF NOT EXISTS rpt_snapshot (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  report_id   BIGINT UNSIGNED NOT NULL,
  sub_id      BIGINT UNSIGNED DEFAULT NULL,
  file_key    VARCHAR(256) NOT NULL COMMENT 'MinIO对象键',
  generated_at DATETIME(3) NOT NULL,
  expire_at   DATETIME(3) NOT NULL,
  tenant_id   VARCHAR(16) DEFAULT 't01' COMMENT '所属租户',
  PRIMARY KEY (id),
  KEY idx_report_time (report_id, generated_at),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB COMMENT='快照索引(文件在MinIO)';

-- ---------------- 集成域 itg_ / 评测域 evl_ ----------------
CREATE TABLE IF NOT EXISTS itg_datasource (
  id         BIGINT UNSIGNED AUTO_INCREMENT,
  ds_code    VARCHAR(64) NOT NULL,
  ds_name    VARCHAR(128) NOT NULL,
  sync_mode  TINYINT NOT NULL COMMENT '1T+1 2CDC',
  cred_ref   VARCHAR(256) NOT NULL COMMENT 'Vault凭证引用',
  schedule_cron VARCHAR(32) DEFAULT NULL,
  status     TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id), UNIQUE KEY uk_ds_code (ds_code)
) ENGINE=InnoDB COMMENT='数据源注册';

CREATE TABLE IF NOT EXISTS itg_sync_task (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  ds_id       BIGINT UNSIGNED NOT NULL,
  task_type   TINYINT NOT NULL COMMENT '1全量 2增量 3质量校验 4重灌',
  biz_date    DATE NOT NULL,
  exec_state  TINYINT NOT NULL DEFAULT 0 COMMENT '0待执行 1运行中 2成功 3重试中 4失败 5延迟',
  rows_read   BIGINT DEFAULT 0, rows_written BIGINT DEFAULT 0,
  fail_reason VARCHAR(1024) DEFAULT NULL,
  started_at  DATETIME(3) DEFAULT NULL, finished_at DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ds_date (ds_id, biz_date),
  KEY idx_state_date (exec_state, biz_date)
) ENGINE=InnoDB COMMENT='同步任务';

CREATE TABLE IF NOT EXISTS evl_eval_question (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  question    VARCHAR(512) NOT NULL,
  scene_tag   VARCHAR(32) NOT NULL,
  expect_json JSON NOT NULL,
  last_result TINYINT DEFAULT NULL,
  source_type TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  KEY idx_scene (scene_tag, last_result)
) ENGINE=InnoDB COMMENT='标注问句集';

-- ---------------- 审计域 aud_ ----------------
CREATE TABLE IF NOT EXISTS aud_audit_log (
  id           BIGINT UNSIGNED AUTO_INCREMENT,
  trace_id     VARCHAR(64) NOT NULL,
  event_type   VARCHAR(32) NOT NULL,
  user_no      VARCHAR(64) NOT NULL,
  action_time  DATETIME(3) NOT NULL,
  object_type  VARCHAR(32) DEFAULT NULL,
  object_id    VARCHAR(64) DEFAULT NULL,
  detail_json  JSON DEFAULT NULL,
  is_sensitive TINYINT(1) NOT NULL DEFAULT 0,
  ip_addr      VARCHAR(45) DEFAULT NULL,
  tenant_id    VARCHAR(16) DEFAULT 't01' COMMENT '所属租户',
  PRIMARY KEY (id, action_time),
  KEY idx_user_time (user_no, action_time),
  KEY idx_sensitive (is_sensitive, action_time),
  KEY idx_trace (trace_id)
) ENGINE=InnoDB
COMMENT='审计日志(按月RANGE分区,保留3年)'
PARTITION BY RANGE (TO_DAYS(action_time)) (
  PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
  PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01'))
);

-- ---------------- LLM 大模型配置域 llm_（阶段1：LLM 配置管理 / 版本快照 / 部署状态） ----------------
CREATE TABLE IF NOT EXISTS llm_model_config (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  model_code  VARCHAR(64)  NOT NULL COMMENT '模型编码（唯一）',
  model_name  VARCHAR(128) NOT NULL COMMENT '模型名称',
  vendor      VARCHAR(32)  DEFAULT 'openai' COMMENT '厂商：openai/qwen/deepseek/ollama',
  base_url    VARCHAR(512) DEFAULT NULL COMMENT 'API Base URL',
  api_key     VARCHAR(512) DEFAULT NULL COMMENT 'API Key（L3 敏感，禁止入审计）',
  model       VARCHAR(128) DEFAULT NULL COMMENT '模型标识，如 gpt-4o',
  temperature DECIMAL(4,2) DEFAULT 0.20 COMMENT '采样温度',
  max_tokens  INT          DEFAULT 4096 COMMENT '最大输出 token 数',
  deploy_url  VARCHAR(512) DEFAULT NULL COMMENT 'Python 运行时部署地址',
  tenant_id   VARCHAR(16)  DEFAULT NULL COMMENT '所属租户；NULL=系统默认（未配置租户时回退）',
  status      TINYINT      DEFAULT 1 COMMENT '1启用 0停用',
  is_deleted  TINYINT(1)   DEFAULT 0,
  created_at  DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(64)  DEFAULT NULL,
  updated_at  DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_code (model_code),
  KEY idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB COMMENT='LLM 大模型配置';

CREATE TABLE IF NOT EXISTS llm_model_version (
  id           BIGINT UNSIGNED AUTO_INCREMENT,
  config_id    BIGINT UNSIGNED NOT NULL COMMENT 'FK→llm_model_config.id',
  version_no   INT    NOT NULL COMMENT '版本号（每次变更+1）',
  config_json  TEXT   NOT NULL COMMENT '全量配置快照（JSON）',
  apply_result VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED/ROLLBACK',
  applied_at   DATETIME(3) DEFAULT NULL,
  applied_by   VARCHAR(64) DEFAULT NULL,
  change_note  VARCHAR(512) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_version (config_id, version_no)
) ENGINE=InnoDB COMMENT='LLM 配置版本快照';

CREATE TABLE IF NOT EXISTS llm_deploy_state (
  id              BIGINT UNSIGNED AUTO_INCREMENT,
  config_id       BIGINT UNSIGNED NOT NULL COMMENT 'FK→llm_model_config.id',
  state           VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/APPLYING/ACTIVE/FAILED',
  health_status   VARCHAR(16) DEFAULT 'UNKNOWN' COMMENT 'UP/DOWN/UNKNOWN',
  latency_ms      INT DEFAULT NULL COMMENT '最近健康检查耗时',
  llm_profile     VARCHAR(16) DEFAULT NULL COMMENT '运行时 profile：mock/openai',
  last_checked_at DATETIME(3) DEFAULT NULL,
  updated_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_deploy_config (config_id)
) ENGINE=InnoDB COMMENT='LLM 部署状态与健康检查';

-- ---------------- 报表导出任务 rpt_export_task（阶段3：导出落库，DB 为唯一事实源） ----------------
CREATE TABLE IF NOT EXISTS rpt_export_task (
  id           BIGINT UNSIGNED AUTO_INCREMENT,
  export_id    VARCHAR(64) NOT NULL COMMENT '导出ID（exp_+UUID短码，唯一）',
  report_id    BIGINT UNSIGNED NOT NULL COMMENT 'FK→rpt_report.id',
  tenant_id    VARCHAR(16) NOT NULL COMMENT '任务所属租户',
  owner_emp_no VARCHAR(64) DEFAULT NULL COMMENT '任务发起人工号',
  format       VARCHAR(8)  DEFAULT NULL COMMENT 'CSV/XLSX/PDF',
  status       VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/COMPLETED/FAILED',
  row_count    INT DEFAULT NULL COMMENT '导出行数（≤5000）',
  file_blob    LONGBLOB DEFAULT NULL COMMENT '导出文件字节',
  expires_at   DATETIME(3) DEFAULT NULL COMMENT '下载有效期（创建+15min）',
  created_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_export_id (export_id),
  KEY idx_export_tenant_owner (tenant_id, owner_emp_no, created_at)
) ENGINE=InnoDB COMMENT='报表导出任务';

-- ---------------- 系统设置 t_sys_setting（P1：键值配置，系统默认 LLM 档位/模拟部署开关） ----------------
CREATE TABLE IF NOT EXISTS t_sys_setting (
  id            BIGINT UNSIGNED AUTO_INCREMENT,
  setting_key   VARCHAR(128) NOT NULL COMMENT '配置键（唯一）',
  setting_value VARCHAR(512) NOT NULL COMMENT '配置值',
  description   VARCHAR(256) DEFAULT NULL COMMENT '配置说明',
  created_at    DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(64)  DEFAULT NULL,
  updated_at    DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by    VARCHAR(64)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_setting_key (setting_key)
) ENGINE=InnoDB COMMENT='系统设置键值表';
