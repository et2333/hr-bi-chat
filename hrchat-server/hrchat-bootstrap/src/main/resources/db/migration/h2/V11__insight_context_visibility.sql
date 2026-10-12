-- A7/D2: 洞察任务上下文可见域标识（隔离生成键与结果选择）。
-- 由服务端按来源身份 + 授权可见域解析，客户端不可自选以提高可见范围。
ALTER TABLE rpt_insight_task ADD COLUMN context_visibility_ref VARCHAR(64) DEFAULT NULL;
