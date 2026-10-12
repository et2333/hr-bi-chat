-- R3: 版本级不可变分析来源上下文（生成时冻结口径）。
-- 读取旧版本不再依赖可变的 rpt_report.context_json：冻结值存在则优先使用。
ALTER TABLE rpt_data_version
  ADD COLUMN context_json JSON DEFAULT NULL COMMENT '生成时冻结的 ReportContext',
  ADD COLUMN context_completeness VARCHAR(16) DEFAULT NULL COMMENT 'complete/partial';
