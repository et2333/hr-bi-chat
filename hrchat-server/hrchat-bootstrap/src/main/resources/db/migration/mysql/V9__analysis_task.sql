-- I3: 进一步分析任务持久化（来源关联、可信上下文、终态结果）；SSE 事件流仍仅在进程内有效。
CREATE TABLE IF NOT EXISTS cht_analysis_task (
  task_id                VARCHAR(64) NOT NULL,
  tenant_id              VARCHAR(16) DEFAULT NULL,
  user_id                BIGINT UNSIGNED NOT NULL,
  emp_no                 VARCHAR(64) DEFAULT NULL,
  origin_type            VARCHAR(16) NOT NULL COMMENT 'ASK/REPORT',
  source_ask_id          VARCHAR(64) DEFAULT NULL,
  source_report_id       BIGINT UNSIGNED DEFAULT NULL,
  source_data_version_id BIGINT UNSIGNED DEFAULT NULL,
  scope_ref              VARCHAR(128) DEFAULT NULL COMMENT '权限+来源指纹，读取时重新鉴权比对',
  metric_version         INT DEFAULT NULL,
  mode                   VARCHAR(16) DEFAULT NULL,
  status                 VARCHAR(16) NOT NULL COMMENT 'RUNNING/COMPLETED/PARTIAL/FAILED/CANCELLED/INTERRUPTED',
  context_json           JSON DEFAULT NULL,
  orgs_json              JSON DEFAULT NULL,
  result_json            JSON DEFAULT NULL,
  created_at             DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  finished_at            DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (task_id),
  KEY idx_cht_analysis_task (tenant_id, user_id, created_at)
) ENGINE=InnoDB COMMENT='进一步分析任务与结果';
