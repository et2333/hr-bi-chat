-- S0: headcount v2 is evaluated at the requested period end, capped by the demo clock.
-- SQL composition adds hire_date/leave_date predicates; keep the semantic formula free
-- of the old emp_status=1 condition so historical leavers are not excluded.
UPDATE biz_metric_version SET status = 3 WHERE metric_id = 1 AND version_no = 1 AND status = 1;

UPDATE biz_metric
SET formula_expr = 'SELECT COUNT(DISTINCT emp_key) FROM dim_employee',
    calc_scope = '指定日期仍在职人数：hire_date<=时点且(leave_date为空或leave_date>时点)；离职当日不计入；组织按当前人员记录归属，历史调动无法还原',
    updated_by = 's0-migration',
    updated_at = '2026-10-05 00:00:00.000'
WHERE id = 1 AND metric_code = 'headcount';

INSERT INTO biz_metric_version
    (metric_id, version_no, formula_expr, calc_scope, status, submitted_by, approved_by, effective_at, change_note)
VALUES
    (1, 2, 'SELECT COUNT(DISTINCT emp_key) FROM dim_employee',
     '指定日期仍在职人数：hire_date<=时点且(leave_date为空或leave_date>时点)；离职当日不计入；组织按当前人员记录归属，历史调动无法还原',
     1, 's0-migration', 's0-migration', '2026-10-05 00:00:00.000',
     'S0历史期末在职口径：改用入离职日期时点判断，保留v1供追溯');

-- The seed includes HR change events through 2026-09-18; its old 09-12 watermark
-- predates those rows. Align the demo HRIS sync marker with the frozen 09-28 clock.
UPDATE itg_sync_task
SET biz_date = '2026-09-28',
    started_at = '2026-09-28 05:30:00.000',
    finished_at = '2026-09-28 06:00:00.000'
WHERE ds_id = 1 AND biz_date = '2026-09-12';
