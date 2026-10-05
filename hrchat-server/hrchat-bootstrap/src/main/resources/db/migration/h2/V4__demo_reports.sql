-- 演示样例报表（冷启动报表中心可见）；owner=hr01(id=1)
INSERT INTO rpt_report (id, report_name, owner_id, source_type, template_id, params_json, status, tenant_id, is_deleted, created_by, updated_by) VALUES
(1, '研发中心人力概览', 1, 3, NULL, '{}', 1, 't01', 0, 'hr01', 'hr01'),
(2, '入职趋势月报', 1, 2, NULL, '{}', 1, 't01', 0, 'hr01', 'hr01');

INSERT INTO rpt_component (report_id, comp_type, chart_type, def_json, sort_no) VALUES
-- METRIC_CARD 不写静态 value：详情页走 metric-card-data 实时查库（与 BAR 同源权限）
(1, 3, NULL, '{"metric":"headcount","title":"在职人数","unit":"人","definition":"期末在职人数（不含待入职）"}', 1),
(1, 1, 'BAR', '{"metric":"headcount","dims":["org"],"title":"按组织对比"}', 2),
(1, 2, NULL, '{"metric":"headcount","dims":["org"],"title":"组织明细"}', 3),
(2, 3, NULL, '{"metric":"hire_count","title":"入职人数","unit":"人","definition":"统计周期内入职人数"}', 1),
(2, 1, 'LINE', '{"metric":"hire_count","dims":["time"],"title":"近三月入职趋势"}', 2),
(2, 2, NULL, '{"metric":"hire_count","dims":["time"],"title":"月度明细"}', 3);

-- 可选订阅（演示「我订阅的」不为空）：hr01 订阅报表 1
INSERT INTO rpt_subscription (id, report_id, freq, channel, next_run_at, status, perm_snapshot_json, tenant_id, is_deleted, created_by, updated_by) VALUES
(1, 1, 1, 1, '2026-10-05 09:00:00.000', 1, '{}', 't01', 0, 'hr01', 'hr01');

INSERT INTO rpt_sub_receiver (subscription_id, user_id, push_status) VALUES
(1, 1, 0);
