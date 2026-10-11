-- I0: ASK 来源与报表数据版本 / 视图快照
ALTER TABLE rpt_report
  ADD COLUMN source_ask_id VARCHAR(64) DEFAULT NULL COMMENT 'ASK 来源 askId，与 template_id 分离',
  ADD COLUMN definition_revision INT NOT NULL DEFAULT 1 COMMENT '定义修订号',
  ADD COLUMN current_data_version_id BIGINT UNSIGNED DEFAULT NULL COMMENT '当前成功数据版本',
  ADD COLUMN context_json JSON DEFAULT NULL COMMENT 'ReportContext 快照',
  ADD COLUMN context_completeness VARCHAR(16) DEFAULT 'partial' COMMENT 'complete/partial',
  ADD COLUMN snapshot_reuse TINYINT DEFAULT NULL COMMENT '创建时是否复用源答案快照';

CREATE TABLE IF NOT EXISTS rpt_data_version (
  id                   BIGINT UNSIGNED AUTO_INCREMENT,
  report_id            BIGINT UNSIGNED NOT NULL,
  version_no           INT NOT NULL,
  definition_revision  INT NOT NULL,
  status               VARCHAR(16) NOT NULL COMMENT 'PENDING/RUNNING/READY/FAILED',
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
  UNIQUE KEY uk_rpt_data_idem (tenant_id, report_id, idempotency_key),
  KEY idx_rpt_data_ver_report (report_id, status)
) ENGINE=InnoDB COMMENT='报表数据版本';

CREATE TABLE IF NOT EXISTS rpt_view_snapshot (
  id               BIGINT UNSIGNED AUTO_INCREMENT,
  data_version_id  BIGINT UNSIGNED NOT NULL,
  component_id     BIGINT UNSIGNED NOT NULL,
  view_key         VARCHAR(128) NOT NULL,
  chart_json       JSON DEFAULT NULL,
  aggregate_json   JSON DEFAULT NULL,
  created_at       DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_view_snap (data_version_id, component_id, view_key)
) ENGINE=InnoDB COMMENT='同版视图快照';

CREATE INDEX idx_rpt_report_source_ask ON rpt_report(source_ask_id);

-- 旧演示趋势组件缺 months：补齐滚动近三月
UPDATE rpt_component
SET def_json = CAST('{"metric":"hire_count","dims":["time"],"dimensions":["time"],"title":"近三月入职趋势","periodKind":"ROLLING","rollingRule":"LAST_N_MONTHS","months":3}' AS JSON)
WHERE report_id = 2 AND comp_type = 1 AND chart_type = 'LINE'
  AND CAST(def_json AS CHAR) LIKE '%"dims":["time"]%'
  AND CAST(def_json AS CHAR) NOT LIKE '%"months"%';

UPDATE rpt_component
SET def_json = CAST('{"metric":"hire_count","dims":["time"],"dimensions":["time"],"title":"月度明细","periodKind":"ROLLING","rollingRule":"LAST_N_MONTHS","months":3}' AS JSON)
WHERE report_id = 2 AND comp_type = 2
  AND CAST(def_json AS CHAR) LIKE '%"dims":["time"]%'
  AND CAST(def_json AS CHAR) NOT LIKE '%"months"%';
