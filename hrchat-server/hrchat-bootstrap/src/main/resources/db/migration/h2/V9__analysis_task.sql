-- I3: 进一步分析任务持久化（来源关联、可信上下文、终态结果）；SSE 事件流仍仅在进程内有效。
CREATE TABLE cht_analysis_task (
  task_id                VARCHAR(64) NOT NULL,
  tenant_id              VARCHAR(16) DEFAULT NULL,
  user_id                BIGINT NOT NULL,
  emp_no                 VARCHAR(64) DEFAULT NULL,
  origin_type            VARCHAR(16) NOT NULL,
  source_ask_id          VARCHAR(64) DEFAULT NULL,
  source_report_id       BIGINT DEFAULT NULL,
  source_data_version_id BIGINT DEFAULT NULL,
  scope_ref              VARCHAR(128) DEFAULT NULL,
  metric_version         INT DEFAULT NULL,
  mode                   VARCHAR(16) DEFAULT NULL,
  status                 VARCHAR(16) NOT NULL,
  context_json           CLOB DEFAULT NULL,
  orgs_json              CLOB DEFAULT NULL,
  result_json            CLOB DEFAULT NULL,
  created_at             DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  finished_at            DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (task_id)
);

CREATE INDEX idx_cht_analysis_task ON cht_analysis_task(tenant_id, user_id, created_at);
