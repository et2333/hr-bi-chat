-- =====================================================================
-- HR智能问数 H2 本地兼容 Schema（MODE=MySQL）
-- 与 hrchat-deploy/sql/schema.sql（MySQL 生产版）保持结构一致：
--   ① 去掉 ENGINE/UNSIGNED/PARTITION 等 MySQL 特有语法
--   ② JSON 用 TEXT 存储（规避 H2 JSON 列 getString 双重编码问题，Java 端 getString 即得纯 JSON 文本）
--   ③ 追加本地演示所需的 Doris 风格表（dim_org/dim_employee/fact_*）
-- =====================================================================

-- ---------------- 多租户域 t_ ---------------- 
CREATE TABLE t_tenant (
  id                 BIGINT AUTO_INCREMENT,
  tenant_code        VARCHAR(16)  NOT NULL,
  tenant_name        VARCHAR(64)  NOT NULL,
  status             TINYINT      NOT NULL DEFAULT 1,
  user_quota         INT          NOT NULL DEFAULT 500,
  report_quota       INT          NOT NULL DEFAULT 200,
  subscription_quota INT          NOT NULL DEFAULT 50,
  api_daily_quota    INT          NOT NULL DEFAULT 10000,
  created_at         DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  created_by         VARCHAR(64)  DEFAULT NULL,
  updated_at         DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  updated_by         VARCHAR(64)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (tenant_code)
);

-- ---------------- 权限安全域 sec_ ----------------
CREATE TABLE sec_user (
  id              BIGINT AUTO_INCREMENT,
  emp_no          VARCHAR(32)  NOT NULL,
  display_name    VARCHAR(64)  NOT NULL,
  email           VARCHAR(128) DEFAULT NULL,
  org_node_id     BIGINT NOT NULL,
  status          TINYINT NOT NULL DEFAULT 1,
  last_login_at   DATETIME(3) DEFAULT NULL,
  prefs_json      TEXT DEFAULT NULL,
  tenant_id       VARCHAR(16) DEFAULT 't01',
  password_hash   VARCHAR(128) DEFAULT NULL,
  must_change_pwd TINYINT(1) DEFAULT 0,
  is_deleted      TINYINT(1) NOT NULL DEFAULT 0,
  created_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(64) DEFAULT NULL,
  updated_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (emp_no),
  KEY idx_org_node (org_node_id),
  KEY idx_emp_status (status)
);

CREATE TABLE sec_org_node (
  id           BIGINT AUTO_INCREMENT,
  org_code     VARCHAR(64)  NOT NULL,
  org_name     VARCHAR(128) NOT NULL,
  parent_id    BIGINT DEFAULT 0,
  org_path     VARCHAR(1024) NOT NULL,
  org_level    TINYINT NOT NULL,
  status       TINYINT NOT NULL DEFAULT 1,
  hr_effect_at DATETIME(3) DEFAULT NULL,
  tenant_id    VARCHAR(16) DEFAULT 't01',
  is_deleted   TINYINT(1) NOT NULL DEFAULT 0,
  created_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by   VARCHAR(64) DEFAULT NULL,
  updated_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by   VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_parent (parent_id),
  KEY idx_org_path (org_path)
);

CREATE TABLE sec_role (
  id         BIGINT AUTO_INCREMENT,
  role_code  VARCHAR(64) NOT NULL,
  role_name  VARCHAR(64) NOT NULL,
  data_level TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE (role_code)
);

CREATE TABLE sec_user_role (
  id         BIGINT AUTO_INCREMENT,
  user_id    BIGINT NOT NULL,
  role_code  VARCHAR(64) NOT NULL,
  granted_by VARCHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (user_id, role_code),
  KEY idx_role (role_code)
);

CREATE TABLE sec_org_grant (
  id           BIGINT AUTO_INCREMENT,
  grantee_type TINYINT NOT NULL,
  grantee_id   VARCHAR(64) NOT NULL,
  org_node_id  BIGINT NOT NULL,
  grant_scope  TINYINT NOT NULL DEFAULT 1,
  effective_at DATETIME(3) NOT NULL,
  expire_at    DATETIME(3) DEFAULT NULL,
  source_type  TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE (grantee_type, grantee_id, org_node_id),
  KEY idx_grantee (grantee_type, grantee_id, expire_at),
  KEY idx_org (org_node_id)
);

CREATE TABLE sec_field_policy (
  id                BIGINT AUTO_INCREMENT,
  field_code        VARCHAR(128) NOT NULL,
  domain            VARCHAR(32)  NOT NULL,
  policy_type       TINYINT NOT NULL,
  role_code         VARCHAR(64)  NOT NULL,
  min_group_size    INT DEFAULT NULL,
  approval_required TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE (field_code, role_code),
  KEY idx_domain (domain)
);

-- ---------------- 语义层域 biz_ ----------------
CREATE TABLE biz_metric (
  id             BIGINT AUTO_INCREMENT,
  metric_code    VARCHAR(64)  NOT NULL,
  metric_name    VARCHAR(128) NOT NULL,
  domain         VARCHAR(32)  NOT NULL,
  formula_expr   VARCHAR(1024) NOT NULL,
  calc_scope     VARCHAR(512) NOT NULL,
  default_period VARCHAR(16)  DEFAULT 'MONTH',
  good_direction TINYINT NOT NULL DEFAULT 1,
  perm_level     TINYINT NOT NULL DEFAULT 1,
  status         TINYINT NOT NULL DEFAULT 1,
  is_deleted     TINYINT(1) NOT NULL DEFAULT 0,
  created_at     DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by     VARCHAR(64) DEFAULT NULL,
  updated_at     DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by     VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (metric_code),
  KEY idx_domain_status (domain, status)
);

CREATE TABLE biz_metric_version (
  id           BIGINT AUTO_INCREMENT,
  metric_id    BIGINT NOT NULL,
  version_no   INT    NOT NULL,
  formula_expr VARCHAR(1024) NOT NULL,
  calc_scope   VARCHAR(512) NOT NULL,
  status       TINYINT NOT NULL DEFAULT 0,
  submitted_by VARCHAR(64) NOT NULL,
  submitted_at DATETIME(3) DEFAULT NULL,
  approved_by  VARCHAR(64) DEFAULT NULL,
  effective_at DATETIME(3) DEFAULT NULL,
  change_note  VARCHAR(512) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (metric_id, version_no),
  KEY idx_version_status (status)
);

CREATE TABLE biz_dimension (
  id         BIGINT AUTO_INCREMENT,
  dim_code   VARCHAR(64) NOT NULL,
  dim_name   VARCHAR(64) NOT NULL,
  dim_type   TINYINT NOT NULL,
  ref_table  VARCHAR(128) DEFAULT NULL,
  key_column     VARCHAR(64) DEFAULT NULL,
  value_column   VARCHAR(64) DEFAULT NULL,
  parent_column  VARCHAR(64) DEFAULT NULL,
  fact_column    VARCHAR(64) DEFAULT NULL,
  current_column VARCHAR(64) DEFAULT NULL,
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE (dim_code)
);

CREATE TABLE biz_dim_value (
  id          BIGINT AUTO_INCREMENT,
  dim_id      BIGINT NOT NULL,
  value_code  VARCHAR(64)  NOT NULL,
  value_label VARCHAR(128) NOT NULL,
  sort_no     INT NOT NULL DEFAULT 0,
  parent_code VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (dim_id, value_code)
);

CREATE TABLE biz_metric_dim (
  id        BIGINT AUTO_INCREMENT,
  metric_id BIGINT NOT NULL,
  dim_id    BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (metric_id, dim_id)
);

CREATE TABLE biz_synonym (
  id          BIGINT AUTO_INCREMENT,
  term_group  VARCHAR(128) NOT NULL,
  target_type TINYINT NOT NULL,
  target_id   BIGINT NOT NULL,
  hit_count   BIGINT NOT NULL DEFAULT 0,
  status      TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  KEY idx_term (term_group),
  KEY idx_target (target_type, target_id)
);

-- ---------------- 会话域 cht_ ----------------
CREATE TABLE cht_session (
  id             BIGINT AUTO_INCREMENT,
  user_id        BIGINT NOT NULL,
  title          VARCHAR(128) NOT NULL,
  status         TINYINT NOT NULL DEFAULT 1,
  last_active_at DATETIME(3) NOT NULL,
  is_pinned      TINYINT(1) NOT NULL DEFAULT 0,
  tenant_id      VARCHAR(16) DEFAULT 't01',
  is_deleted     TINYINT(1) NOT NULL DEFAULT 0,
  created_at     DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by     VARCHAR(64) DEFAULT NULL,
  updated_at     DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by     VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_user_status (user_id, status, last_active_at)
);

CREATE TABLE cht_turn (
  id            BIGINT AUTO_INCREMENT,
  session_id    BIGINT NOT NULL,
  turn_seq      INT NOT NULL,
  question_text VARCHAR(1024) NOT NULL,
  intent_type   TINYINT DEFAULT NULL,
  inherit_json  TEXT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (session_id, turn_seq),
  KEY idx_session (session_id)
);

CREATE TABLE cht_answer (
  id              BIGINT AUTO_INCREMENT,
  turn_id         BIGINT NOT NULL,
  answer_state    TINYINT NOT NULL,
  summary_text    VARCHAR(1024) DEFAULT NULL,
  result_ref      VARCHAR(128) DEFAULT NULL,
  total_rows      INT NOT NULL DEFAULT 0,
  chart_type      VARCHAR(32) DEFAULT NULL,
  metric_ids      VARCHAR(255) DEFAULT NULL,
  metric_versions VARCHAR(128) DEFAULT NULL,
  data_fresh_at   DATETIME(3) DEFAULT NULL,
  latency_ms      INT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (turn_id),
  KEY idx_state (answer_state)
);

CREATE TABLE cht_clarify (
  id             BIGINT AUTO_INCREMENT,
  turn_id        BIGINT NOT NULL,
  ambiguity_type TINYINT NOT NULL,
  question_text  VARCHAR(256) NOT NULL,
  options_json   TEXT NOT NULL,
  selected_code  VARCHAR(64) DEFAULT NULL,
  saved_as_pref  TINYINT(1) DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_turn (turn_id)
);

CREATE TABLE cht_feedback (
  id      BIGINT AUTO_INCREMENT,
  turn_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  rating  TINYINT NOT NULL,
  reason  TINYINT DEFAULT NULL,
  comment VARCHAR(512) DEFAULT NULL,
  handled TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (turn_id, user_id),
  KEY idx_handled (handled, created_at)
);

-- ---------------- 报表域 rpt_ ----------------
CREATE TABLE rpt_report (
  id              BIGINT AUTO_INCREMENT,
  report_name     VARCHAR(64) NOT NULL,
  owner_id        BIGINT NOT NULL,
  source_type     TINYINT NOT NULL,
  template_id     BIGINT DEFAULT NULL,
  params_json     TEXT NOT NULL,
  status          TINYINT NOT NULL DEFAULT 1,
  metric_versions VARCHAR(255) DEFAULT NULL,
  tenant_id       VARCHAR(16) DEFAULT 't01',
  is_deleted      TINYINT(1) NOT NULL DEFAULT 0,
  created_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(64) DEFAULT NULL,
  updated_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_owner (owner_id, status),
  KEY idx_name (report_name)
);

CREATE TABLE rpt_component (
  id         BIGINT AUTO_INCREMENT,
  report_id  BIGINT NOT NULL,
  comp_type  TINYINT NOT NULL,
  chart_type VARCHAR(32) DEFAULT NULL,
  def_json   TEXT NOT NULL,
  sort_no    INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_comp_report (report_id)
);

CREATE TABLE rpt_subscription (
  id                 BIGINT AUTO_INCREMENT,
  report_id          BIGINT NOT NULL,
  freq               TINYINT NOT NULL,
  channel            TINYINT NOT NULL,
  next_run_at        DATETIME(3) NOT NULL,
  status             TINYINT NOT NULL DEFAULT 1,
  perm_snapshot_json TEXT DEFAULT NULL,
  tenant_id          VARCHAR(16) DEFAULT 't01',
  is_deleted         TINYINT(1) NOT NULL DEFAULT 0,
  created_at         DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by         VARCHAR(64) DEFAULT NULL,
  updated_at         DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by         VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_next_run (status, next_run_at),
  KEY idx_sub_report (report_id)
);

CREATE TABLE rpt_sub_receiver (
  id              BIGINT AUTO_INCREMENT,
  subscription_id BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  push_status     TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE (subscription_id, user_id)
);

CREATE TABLE rpt_snapshot (
  id           BIGINT AUTO_INCREMENT,
  report_id    BIGINT NOT NULL,
  sub_id       BIGINT DEFAULT NULL,
  file_key     VARCHAR(256) NOT NULL,
  generated_at DATETIME(3) NOT NULL,
  expire_at    DATETIME(3) NOT NULL,
  tenant_id    VARCHAR(16) DEFAULT 't01',
  PRIMARY KEY (id),
  KEY idx_report_time (report_id, generated_at),
  KEY idx_expire (expire_at)
);

-- ---------------- 集成域 itg_ / 评测域 evl_ ----------------
CREATE TABLE itg_datasource (
  id            BIGINT AUTO_INCREMENT,
  ds_code       VARCHAR(64) NOT NULL,
  ds_name       VARCHAR(128) NOT NULL,
  sync_mode     TINYINT NOT NULL,
  cred_ref      VARCHAR(256) NOT NULL,
  schedule_cron VARCHAR(32) DEFAULT NULL,
  status        TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE (ds_code)
);

CREATE TABLE itg_sync_task (
  id           BIGINT AUTO_INCREMENT,
  ds_id        BIGINT NOT NULL,
  task_type    TINYINT NOT NULL,
  biz_date     DATE NOT NULL,
  exec_state   TINYINT NOT NULL DEFAULT 0,
  rows_read    BIGINT DEFAULT 0,
  rows_written BIGINT DEFAULT 0,
  fail_reason  VARCHAR(1024) DEFAULT NULL,
  started_at   DATETIME(3) DEFAULT NULL,
  finished_at  DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ds_date (ds_id, biz_date),
  KEY idx_state_date (exec_state, biz_date)
);

CREATE TABLE evl_eval_question (
  id          BIGINT AUTO_INCREMENT,
  question    VARCHAR(512) NOT NULL,
  scene_tag   VARCHAR(32) NOT NULL,
  expect_json TEXT NOT NULL,
  last_result TINYINT DEFAULT NULL,
  source_type TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  KEY idx_scene (scene_tag, last_result)
);

-- ---------------- 审计域 aud_ ----------------
CREATE TABLE aud_audit_log (
  id           BIGINT AUTO_INCREMENT,
  trace_id     VARCHAR(64) NOT NULL,
  event_type   VARCHAR(32) NOT NULL,
  user_no      VARCHAR(64) NOT NULL,
  action_time  DATETIME(3) NOT NULL,
  object_type  VARCHAR(32) DEFAULT NULL,
  object_id    VARCHAR(64) DEFAULT NULL,
  detail_json  TEXT DEFAULT NULL,
  is_sensitive TINYINT(1) NOT NULL DEFAULT 0,
  ip_addr      VARCHAR(45) DEFAULT NULL,
  tenant_id    VARCHAR(16) DEFAULT 't01',
  PRIMARY KEY (id),
  KEY idx_user_time (user_no, action_time),
  KEY idx_sensitive (is_sensitive, action_time),
  KEY idx_trace (trace_id)
);

-- =====================================================================
-- 本地演示：Doris 风格表（prod 由 query-exec 直连 Doris，此处以 H2 代替）
-- =====================================================================

CREATE TABLE dim_org (
  org_key       INT NOT NULL,
  org_code      VARCHAR(64) NOT NULL,
  org_name      VARCHAR(128) NOT NULL,
  parent_org_key INT NOT NULL,
  org_level     TINYINT NOT NULL,
  org_path      VARCHAR(1024) NOT NULL,
  valid_from    DATE NOT NULL,
  valid_to      DATE NOT NULL DEFAULT '9999-12-31',
  is_current    TINYINT(1) NOT NULL DEFAULT 1,
  tenant_id     VARCHAR(16) DEFAULT 't01'
);

CREATE TABLE dim_employee (
  emp_key       INT NOT NULL,
  emp_no        VARCHAR(32) NOT NULL,
  emp_name      VARCHAR(64) NOT NULL,
  org_key       INT NOT NULL,
  job_level     VARCHAR(16),
  job_family    VARCHAR(32),
  gender        TINYINT,
  hire_date     DATE NOT NULL,
  tenure_band   VARCHAR(16),
  emp_status    TINYINT,
  leave_date    DATE DEFAULT NULL,
  id_card_mask  VARCHAR(32),
  mobile_mask   VARCHAR(16),
  valid_from    DATE NOT NULL,
  valid_to      DATE NOT NULL DEFAULT '9999-12-31',
  is_current    TINYINT(1) NOT NULL DEFAULT 1,
  tenant_id     VARCHAR(16) DEFAULT 't01'
);

CREATE TABLE fact_emp_change (
  dt           DATE NOT NULL,
  emp_key      INT NOT NULL,
  org_key      INT NOT NULL,
  change_type  TINYINT NOT NULL,
  leave_reason VARCHAR(64) DEFAULT NULL,
  change_date  DATE NOT NULL,
  src_time     DATETIME NOT NULL,
  etl_time     DATETIME NOT NULL,
  tenant_id    VARCHAR(16) DEFAULT 't01'
);

CREATE TABLE fact_payroll_month (
  dt         DATE NOT NULL,
  emp_key    INT NOT NULL,
  org_key    INT NOT NULL,
  gross_pay  DECIMAL(12,2) NOT NULL,
  net_pay    DECIMAL(12,2),
  bonus      DECIMAL(12,2),
  social_emp DECIMAL(12,2),
  fund_emp   DECIMAL(12,2),
  tenant_id  VARCHAR(16) DEFAULT 't01'
);

-- 考勤日事实：attended 非空=出勤（缺勤日记 NULL），COUNT(attended)/COUNT(*)=出勤率
CREATE TABLE fact_attendance_daily (
  dt        DATE NOT NULL,
  emp_key   INT NOT NULL,
  org_key   INT NOT NULL,
  attended  TINYINT DEFAULT NULL,
  tenant_id VARCHAR(16) DEFAULT 't01'
);

-- 绩效周期事实：score 为 5 分制周期评分
CREATE TABLE fact_performance_cycle (
  dt        DATE NOT NULL,
  emp_key   INT NOT NULL,
  org_key   INT NOT NULL,
  cycle     VARCHAR(16) NOT NULL,
  score     DECIMAL(4,2) NOT NULL,
  tenant_id VARCHAR(16) DEFAULT 't01'
);

-- =====================================================================
-- LLM 大模型配置域 llm_（阶段1：LLM 配置管理 / 版本快照 / 部署状态）
-- =====================================================================

CREATE TABLE llm_model_config (
  id          BIGINT AUTO_INCREMENT,
  model_code  VARCHAR(64)  NOT NULL,
  model_name  VARCHAR(128) NOT NULL,
  vendor      VARCHAR(32)  DEFAULT 'openai',
  base_url    VARCHAR(512) DEFAULT NULL,
  api_key     VARCHAR(512) DEFAULT NULL,
  model       VARCHAR(128) DEFAULT NULL,
  temperature DECIMAL(4,2) DEFAULT 0.20,
  max_tokens  INT          DEFAULT 4096,
  deploy_url  VARCHAR(512) DEFAULT NULL,
  tenant_id   VARCHAR(16)  DEFAULT NULL,
  status      TINYINT      DEFAULT 1,
  is_deleted  TINYINT(1)   DEFAULT 0,
  created_at  DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(64)  DEFAULT NULL,
  updated_at  DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (model_code)
);

CREATE TABLE llm_model_version (
  id           BIGINT AUTO_INCREMENT,
  config_id    BIGINT NOT NULL,
  version_no   INT    NOT NULL,
  config_json  TEXT   NOT NULL,
  apply_result VARCHAR(16) DEFAULT 'PENDING',
  applied_at   DATETIME(3) DEFAULT NULL,
  applied_by   VARCHAR(64) DEFAULT NULL,
  change_note  VARCHAR(512) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (config_id, version_no)
);

CREATE TABLE llm_deploy_state (
  id              BIGINT AUTO_INCREMENT,
  config_id       BIGINT NOT NULL,
  state           VARCHAR(16) DEFAULT 'PENDING',
  health_status   VARCHAR(16) DEFAULT 'UNKNOWN',
  latency_ms      INT DEFAULT NULL,
  llm_profile     VARCHAR(16) DEFAULT NULL,
  last_checked_at DATETIME(3) DEFAULT NULL,
  updated_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE (config_id)
);

-- =====================================================================
-- 报表导出任务 rpt_export_task（阶段3：导出落库，DB 为唯一事实源）
-- =====================================================================

CREATE TABLE rpt_export_task (
  id           BIGINT AUTO_INCREMENT,
  export_id    VARCHAR(64)  NOT NULL,
  report_id    BIGINT       NOT NULL,
  owner_emp_no VARCHAR(64)  DEFAULT NULL,
  format       VARCHAR(8)   DEFAULT NULL,
  status       VARCHAR(16)  DEFAULT 'PENDING',
  row_count    INT          DEFAULT NULL,
  file_blob    BLOB         DEFAULT NULL,
  expires_at   DATETIME(3)  DEFAULT NULL,
  created_at   DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE (export_id)
);

-- =====================================================================
-- 系统设置 t_sys_setting（P1：键值配置，系统默认 LLM 档位/模拟部署开关）
-- =====================================================================

CREATE TABLE t_sys_setting (
  id            BIGINT AUTO_INCREMENT,
  setting_key   VARCHAR(128) NOT NULL,
  setting_value VARCHAR(512) NOT NULL,
  description   VARCHAR(256) DEFAULT NULL,
  created_at    DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(64)  DEFAULT NULL,
  updated_at    DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  updated_by    VARCHAR(64)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (setting_key)
);
