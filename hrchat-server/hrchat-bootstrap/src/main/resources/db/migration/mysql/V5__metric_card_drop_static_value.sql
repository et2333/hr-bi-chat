-- 已跑过 V4 的库：去掉指标卡静态 value，改由详情页实时查库
UPDATE rpt_component
SET def_json = '{"metric":"headcount","title":"在职人数","unit":"人","definition":"期末在职人数（不含待入职）"}'
WHERE report_id = 1 AND comp_type = 3;

UPDATE rpt_component
SET def_json = '{"metric":"hire_count","title":"入职人数","unit":"人","definition":"统计周期内入职人数"}'
WHERE report_id = 2 AND comp_type = 3;
