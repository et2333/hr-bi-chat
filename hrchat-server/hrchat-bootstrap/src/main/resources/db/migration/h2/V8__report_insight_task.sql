-- I2: 报表洞察任务与结果持久化（幂等生成键 + attempt 状态机）
CREATE TABLE rpt_insight_task (
  id                   BIGINT AUTO_INCREMENT,
  generation_key       VARCHAR(128) NOT NULL,
  tenant_id            VARCHAR(16) DEFAULT NULL,
  report_id            BIGINT NOT NULL,
  component_id         BIGINT NOT NULL,
  data_version_id      BIGINT NOT NULL,
  view_key             VARCHAR(128) NOT NULL,
  scope_ref            VARCHAR(128) DEFAULT NULL,
  metric_version       VARCHAR(64) DEFAULT NULL,
  insight_revision     VARCHAR(64) NOT NULL,
  status               VARCHAR(16) NOT NULL,
  attempt              INT NOT NULL DEFAULT 0,
  degradation_reason   VARCHAR(64) DEFAULT NULL,
  failure_reason       VARCHAR(255) DEFAULT NULL,
  result_json          CLOB DEFAULT NULL,
  generation_json      CLOB DEFAULT NULL,
  insight_generated_at DATETIME(3) DEFAULT NULL,
  created_at           DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by           VARCHAR(64) DEFAULT NULL,
  updated_at           DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_by           VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_insight_gen (generation_key)
);

CREATE INDEX idx_rpt_insight_lookup ON rpt_insight_task(report_id, component_id, view_key);
