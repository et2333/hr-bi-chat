-- I0: ASK 来源与报表数据版本 / 视图快照
ALTER TABLE rpt_report ADD COLUMN source_ask_id VARCHAR(64) DEFAULT NULL;
ALTER TABLE rpt_report ADD COLUMN definition_revision INT NOT NULL DEFAULT 1;
ALTER TABLE rpt_report ADD COLUMN current_data_version_id BIGINT DEFAULT NULL;
ALTER TABLE rpt_report ADD COLUMN context_json CLOB DEFAULT NULL;
ALTER TABLE rpt_report ADD COLUMN context_completeness VARCHAR(16) DEFAULT 'partial';
ALTER TABLE rpt_report ADD COLUMN snapshot_reuse TINYINT DEFAULT NULL;

CREATE TABLE rpt_data_version (
  id                   BIGINT AUTO_INCREMENT,
  report_id            BIGINT NOT NULL,
  version_no           INT NOT NULL,
  definition_revision  INT NOT NULL,
  status               VARCHAR(16) NOT NULL,
  data_as_of           DATETIME(3) DEFAULT NULL,
  report_generated_at  DATETIME(3) DEFAULT NULL,
  snapshot_reuse       TINYINT NOT NULL DEFAULT 0,
  scope_ref            VARCHAR(128) DEFAULT NULL,
  idempotency_key      VARCHAR(128) DEFAULT NULL,
  failure_reason       VARCHAR(255) DEFAULT NULL,
  tenant_id            VARCHAR(16) DEFAULT NULL,
  created_at           DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  created_by           VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_data_ver (report_id, version_no),
  UNIQUE KEY uk_rpt_data_idem (tenant_id, report_id, idempotency_key)
);

CREATE TABLE rpt_view_snapshot (
  id               BIGINT AUTO_INCREMENT,
  data_version_id  BIGINT NOT NULL,
  component_id     BIGINT NOT NULL,
  view_key         VARCHAR(128) NOT NULL,
  chart_json       CLOB DEFAULT NULL,
  aggregate_json   CLOB DEFAULT NULL,
  created_at       DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_view_snap (data_version_id, component_id, view_key)
);

CREATE INDEX idx_rpt_report_source_ask ON rpt_report(source_ask_id);
CREATE INDEX idx_rpt_data_ver_report ON rpt_data_version(report_id, status);

-- 旧演示趋势组件缺 months：补齐滚动近三月，避免打开详情 ensure 失败
UPDATE rpt_component
SET def_json = '{"metric":"hire_count","dims":["time"],"dimensions":["time"],"title":"近三月入职趋势","periodKind":"ROLLING","rollingRule":"LAST_N_MONTHS","months":3}'
WHERE report_id = 2 AND comp_type = 1 AND chart_type = 'LINE'
  AND def_json LIKE '%"dims":["time"]%'
  AND def_json NOT LIKE '%"months"%';

UPDATE rpt_component
SET def_json = '{"metric":"hire_count","dims":["time"],"dimensions":["time"],"title":"月度明细","periodKind":"ROLLING","rollingRule":"LAST_N_MONTHS","months":3}'
WHERE report_id = 2 AND comp_type = 2
  AND def_json LIKE '%"dims":["time"]%'
  AND def_json NOT LIKE '%"months"%';
