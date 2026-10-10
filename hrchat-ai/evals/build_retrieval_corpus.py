"""Build and validate R4 retrieval corpus (distractors + example_gold).

Usage:
  python -m evals.build_retrieval_corpus --validate
  python -m evals.build_retrieval_corpus --write
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

CORPUS_DIR = Path(__file__).resolve().parents[1] / "adapters" / "retrieval_corpus"
EXECUTABLE_CODES = ("headcount", "hire_count", "leave_count")
RESERVED_CODES = EXECUTABLE_CODES + (
    "turnover_rate", "payroll_total", "avg_salary", "attendance_rate", "perf_avg",
)
MODES = ("scalar", "org", "trend", "detail")


def compile_example(row):
    from langgraph_flows.query_memory import ContextQueryDraft, compile_contextual
    from langgraph_flows.query_plan import PlanRejected
    catalog = {"as_of_date": "2026-09-28", "timezone": "Asia/Shanghai", "organizations": [], "metrics": [
        {"code": code, "name": METRIC_TEXT[code][0], "aliases": [], "version": 1,
         "allowed_modes": list(MODES), "requires_period": code != "headcount",
         "time_type": "as_of" if code == "headcount" else "period"} for code in EXECUTABLE_CODES]}
    draft = ContextQueryDraft.model_validate(row['draft'])
    try:
        plan, _, _ = compile_contextual(draft, row['question'], catalog, None,
            {"schema_version": "1", "context_version": 1}, 'corpus-validation')
        return {"decision": plan.decision, "reason": plan.reason}
    except PlanRejected as exc:
        # An accurately extracted but imprecise period must lead to clarification,
        # never an invented default. All other compilation defects fail validation.
        if exc.reason == 'unresolved_time' and exc.decision == 'clarify':
            return {"decision": 'clarify', "reason": exc.reason}
        raise
MODE_TEXT = {"scalar": None, "org": "按部门对比", "trend": "趋势", "detail": "明细"}
METRIC_TEXT = {
    "headcount": ("在职人数", "在岗", "在岗人数"),
    "hire_count": ("入职人数", "新入职", "入职人员"),
    "leave_count": ("离职人数", "人员流失数量", "离职人员"),
}
# Mother + paraphrases: no org/tenant IDs in question text (permission-safe few-shot).
MATRIX = {
    ("headcount", "scalar"): [
        "现在有多少人在岗", "现有员工规模是多少", "目前在职人数",
    ],
    ("headcount", "org"): [
        "在职人数按部门对比", "各部门现在分别有多少人", "按组织看在岗人数",
    ],
    ("headcount", "trend"): [
        "在职人数近三月趋势", "近半年员工规模走势", "按月看在职人数变化",
    ],
    ("headcount", "detail"): [
        "在岗人员明细", "现在仍在岗的员工名单", "在册员工花名册",
    ],
    ("hire_count", "scalar"): [
        "本月新入职多少人", "八月份新来公司的人数", "今年新增员工数",
    ],
    ("hire_count", "org"): [
        "入职人数按部门对比", "各部门本月入职多少人", "按组织看新入职人数",
    ],
    ("hire_count", "trend"): [
        "近三月入职人数趋势", "按月看招进来的人数变化", "近半年入职数量走势",
    ],
    ("hire_count", "detail"): [
        "上月入职人员明细", "刚加入公司的员工清单", "上一个月报到人员列表",
    ],
    ("leave_count", "scalar"): [
        "近30天人员流失数量", "八月有多少人离开公司", "近期流失人员规模",
    ],
    ("leave_count", "org"): [
        "本月离职人数按部门对比", "离职人员在哪些部门分布", "各组织离开公司的员工数量",
    ],
    ("leave_count", "trend"): [
        "近三月离职人数趋势", "按月看人员流出的变化", "最近六个月离职数量走势",
    ],
    ("leave_count", "detail"): [
        "离职人员明细", "离开公司的员工名单", "近期离职花名册",
    ],
}

# Near-executable distractors: names deliberately close to headcount/hire/leave.
DISTRACTORS = [
    ("headcount_fte", "在岗折合全职人数", ["FTE在岗", "折合全职人数"], "模拟口径：按工时折合的在岗FTE，本PoC不可查询"),
    ("headcount_excl_probation", "在职人数不含试用", ["转正后在职人数"], "模拟口径：仅统计已转正员工，本PoC不可查询"),
    ("headcount_incl_contractor", "含外包在岗人数", ["含外包人力"], "模拟口径：正式+外包在岗合计，本PoC不可查询"),
    ("authorized_headcount", "编制人数", ["核定编制", "编制规模"], "模拟口径：组织核定编制数，本PoC不可查询"),
    ("vacancy_count", "缺编人数", ["空缺岗位数"], "模拟口径：编制减在职，本PoC不可查询"),
    ("active_roster_count", "在册人数", ["花名册人数"], "模拟口径：花名册在册不计状态，本PoC不可查询"),
    ("on_job_rate", "在岗率", ["到岗率"], "模拟口径：在岗÷应到岗，本PoC不可查询"),
    ("remote_worker_count", "远程办公人数", ["居家办公人数"], "模拟口径：标记为远程的在职人数，本PoC不可查询"),
    ("hire_offer_count", "录用通知发出数", ["Offer数"], "模拟口径：发出offer次数，本PoC不可查询"),
    ("hire_accept_count", "接受录用人数", ["接受Offer人数"], "模拟口径：接受offer人数，本PoC不可查询"),
    ("hire_onboard_plan", "计划入职人数", ["预计入职"], "模拟口径：排期入职尚未报到，本PoC不可查询"),
    ("hire_campus_count", "校招入职人数", ["校园招聘入职"], "模拟口径：校招渠道入职，本PoC不可查询"),
    ("hire_social_count", "社招入职人数", ["社会招聘入职"], "模拟口径：社招渠道入职，本PoC不可查询"),
    ("hire_internal_transfer_in", "内部调入人数", ["调入人数"], "模拟口径：组织间调入，本PoC不可查询"),
    ("time_to_hire_days", "招聘周期天数", ["入职周期"], "模拟口径：从需求到入职中位天数，本PoC不可查询"),
    ("leave_voluntary_count", "主动离职人数", ["主动流失"], "模拟口径：仅主动离职，本PoC不可查询"),
    ("leave_involuntary_count", "被动离职人数", ["辞退人数"], "模拟口径：仅被动离职，本PoC不可查询"),
    ("leave_regret_rate", "遗憾离职占比", ["关键人才流失比"], "模拟口径：绩效高者离职占比，本PoC不可查询"),
    ("attrition_risk_score", "流失风险分", ["离职风险分", "流失评分"], "模拟口径：未来90天离职概率均值，本PoC不可查询"),
    ("turnover_annualized", "年化流失率", ["年化离职率"], "模拟口径：年化主动+被动流失率，本PoC不可查询"),
    ("leave_internal_transfer_out", "内部调出人数", ["调出人数"], "模拟口径：调出不计离职，本PoC不可查询"),
    ("leave_probation_fail", "试用期淘汰人数", ["试用不合格"], "模拟口径：试用期未通过，本PoC不可查询"),
    ("retention_rate_12m", "十二个月留存率", ["周年留存率"], "模拟口径：入职满12个月仍在职比例，本PoC不可查询"),
    ("span_of_control", "管理幅度", ["人均下属数"], "模拟口径：管理者平均下属数，本PoC不可查询"),
    ("manager_ratio", "管理者占比", ["干部占比"], "模拟口径：管理者÷在职，本PoC不可查询"),
    ("gender_ratio_female", "女性员工占比", ["女员工比例"], "模拟口径：在职女性占比，本PoC不可查询"),
    ("avg_tenure_years", "平均司龄", ["人均司龄"], "模拟口径：在职员工平均司龄年，本PoC不可查询"),
    ("overtime_hours_avg", "人均加班时长", ["平均加班小时"], "模拟口径：月人均加班小时，本PoC不可查询"),
    ("absenteeism_rate", "缺勤率", ["旷工率"], "模拟口径：缺勤人天÷应出勤，本PoC不可查询"),
    ("sick_leave_days", "病假人天", ["病假天数合计"], "模拟口径：病假合计人天，本PoC不可查询"),
    ("training_completion_rate", "培训完成率", ["必修课完成率"], "模拟口径：必修培训完成占比，本PoC不可查询"),
    ("promotion_count", "晋升人数", ["职级晋升人次"], "模拟口径：周期内晋升人次，本PoC不可查询"),
    ("demotion_count", "降级人数", ["职级下调"], "模拟口径：周期内降级人次，本PoC不可查询"),
    ("perf_top_ratio", "高绩效占比", ["绩优比例"], "模拟口径：绩效A占比，本PoC不可查询"),
    ("perf_bottom_ratio", "低绩效占比", ["绩差比例"], "模拟口径：绩效D占比，本PoC不可查询"),
    ("salary_median", "薪酬中位数", ["薪资中位"], "模拟口径：应发中位数，本PoC不可查询"),
    ("salary_p90", "薪酬九十分位", ["P90薪资"], "模拟口径：应发P90，本PoC不可查询"),
    ("bonus_rate", "奖金发放率", ["年终奖覆盖率"], "模拟口径：有奖金人数÷在职，本PoC不可查询"),
    ("labor_cost_per_fte", "人均人力成本", ["单FTE成本"], "模拟口径：人力成本÷FTE，本PoC不可查询"),
    ("recruit_cost_per_hire", "单次招聘成本", ["CostPerHire"], "模拟口径：招聘费用÷入职，本PoC不可查询"),
    ("open_req_count", "在招需求数", ["开放HC"], "模拟口径：未关闭招聘需求，本PoC不可查询"),
    ("pipeline_candidate_count", "招聘漏斗候选人", ["候选人存量"], "模拟口径：流程中候选人数，本PoC不可查询"),
    ("interview_pass_rate", "面试通过率", ["面试合格率"], "模拟口径：通过面试÷参加面试，本PoC不可查询"),
    ("offer_accept_rate", "Offer接受率", ["录用接受率"], "模拟口径：接受÷发出，本PoC不可查询"),
    ("contractor_count", "外包人数", ["派遣人数"], "模拟口径：在场外包，本PoC不可查询"),
    ("intern_count", "实习生人数", ["在岗实习"], "模拟口径：在岗实习生，本PoC不可查询"),
    ("rehire_count", "返聘人数", ["重新入职"], "模拟口径：二次入职人数，本PoC不可查询"),
    ("mass_leave_alert", "批量离职预警", ["集中流失预警"], "模拟口径：规则触发的流失预警次数，本PoC不可查询"),
    ("org_health_index", "组织健康指数", ["团队健康分"], "模拟口径：综合问卷指数，本PoC不可查询"),
    ("engagement_score", "敬业度得分", ["员工敬业度"], "模拟口径：敬业度调研均分，本PoC不可查询"),
    ("eNPS", "员工净推荐值", ["eNPS得分"], "模拟口径：推荐者减贬损者，本PoC不可查询"),
    ("compliance_training_due", "合规培训到期人数", ["待训合规人数"], "模拟口径：培训即将过期人数，本PoC不可查询"),
    ("visa_expiring_count", "证件即将到期人数", ["工作许可到期"], "模拟口径：外籍证件到期预警，本PoC不可查询"),
    ("background_check_pending", "背调未完成人数", ["待背调"], "模拟口径：入职前背调未完成，本PoC不可查询"),
    ("payroll_variance", "薪酬差异额", ["薪资波动额"], "模拟口径：环比应发差额，本PoC不可查询"),
    ("headcount_budget_gap", "编制执行差额", ["HC执行差"], "模拟口径：实际在职减预算编制，本PoC不可查询"),
    ("critical_role_vacancy", "关键岗位空缺", ["核心岗缺编"], "模拟口径：标记关键岗位的空缺数，本PoC不可查询"),
    ("succession_ready_count", "继任就绪人数", ["继任池人数"], "模拟口径：继任就绪候选人，本PoC不可查询"),
    ("internal_mobility_rate", "内部流动率", ["内部转岗率"], "模拟口径：周期内内部转岗占比，本PoC不可查询"),
    ("overtime_compliance_breach", "加班合规违约人次", ["超时加班人次"], "模拟口径：超法定加班人次，本PoC不可查询"),
]


def example_rows():
    from datetime import date
    from langgraph_flows.time_intent import time_mentions
    surfaces = {
        'headcount': ['在职人数', '在岗人数', '在岗人员', '在册员工', '员工规模', '员工', '在岗', '人'],
        'hire_count': ['入职人数', '入职人员', '入职数量', '新入职', '新增员工', '报到人员', '加入公司的员工', '招进来的人数', '新来公司的人数', '入职'],
        'leave_count': ['离职人数', '离职人员', '人员流失数量', '离职数量', '人员流出', '离开公司的员工', '流失人员', '离职花名册', '人离开公司'],
    }
    modes = {'scalar': [], 'org': ['按部门对比', '各部门', '按组织', '哪些部门', '各组织'],
             'trend': ['趋势', '走势', '按月', '变化'], 'detail': ['明细', '名单', '清单', '花名册', '列表']}
    rows = []
    for (code, mode), questions in MATRIX.items():
        for i, question in enumerate(questions):
            mentions = time_mentions(question, date(2026, 9, 28))
            period = mentions[0][0] if mentions else next((v for v in ['近半年', '近期'] if v in question), None)
            row = {
                'id': f'ex_{code}_{mode}_{i + 1:02d}', 'role': 'example_gold', 'question': question,
                'draft': {'action': 'query', 'decision': 'execute', 'metric_codes': [code],
                    'metric_text': next(v for v in surfaces[code] if v in question),
                    'organization': None, 'time_expression': period,
                    'query_mode': None if mode == 'scalar' else mode,
                    'mode_text': next((v for v in modes[mode] if v in question), None),
                    'clear_slots': [], 'unsupported_reason': None},
                'source': 'matrix_seed+paraphrase_v1', 'reviewed': False,
                'validation': 'context_compiler_v1', 'human_review': 'not_recorded',
            }
            row['compiled_outcome'] = compile_example(row)
            rows.append(row)
    return rows


def distractor_rows():
    rows = []
    for i, (code, name, aliases, definition) in enumerate(DISTRACTORS, start=1):
        rows.append({
            "id": f"dist_{code}",
            "role": "retrieval_distractor",
            "code": code,
            "name": name,
            "aliases": aliases,
            "definition": definition,
            "allowed_modes": [],
            "executable": False,
            "source": "authored_draft_v1",
            "reviewed": False,
            "human_review": "not_recorded",
            "validation": "schema_role_and_reserved_codes_v1",
        })
    return rows


def load_jsonl(path: Path):
    if not path.is_file():
        raise FileNotFoundError(path)
    rows = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path.name}:{line_no}: {exc}") from exc
    return rows


def write_jsonl(path: Path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


def validate(examples, distractors):
    errors = []
    if not (36 <= len(examples) <= 120):
        errors.append(f"example count {len(examples)} outside 36..120")
    if not (40 <= len(distractors) <= 120):
        errors.append(f"distractor count {len(distractors)} outside 40..120")

    ex_ids, dist_codes = set(), set()
    reviewed_ex = reviewed_dist = 0
    for row in examples:
        if row.get("role") != "example_gold":
            errors.append(f"example {row.get('id')} bad role")
        if row.get("id") in ex_ids:
            errors.append(f"duplicate example id {row.get('id')}")
        ex_ids.add(row.get("id"))
        if row.get("reviewed"):
            reviewed_ex += 1
        q = (row.get("question") or "").strip()
        draft = row.get("draft") or {}
        codes = draft.get("metric_codes") or []
        mode = draft.get("query_mode") or "scalar"
        if not q:
            errors.append(f"{row.get('id')}: empty question")
        if "研发" in q or "org_id" in q.lower():
            errors.append(f"{row.get('id')}: question must not embed org/tenant identifiers")
        if draft.get("decision") != "execute":
            errors.append(f"{row.get('id')}: decision must be execute")
        if len(codes) != 1 or codes[0] not in EXECUTABLE_CODES:
            errors.append(f"{row.get('id')}: metric_codes must be one executable code")
        if mode not in MODES:
            errors.append(f"{row.get('id')}: bad query_mode {mode}")
        if draft.get("organization") not in (None,):
            errors.append(f"{row.get('id')}: organization must be null in few-shot corpus")
        if "SELECT" in (draft.get("metric_text") or "").upper():
            errors.append(f"{row.get('id')}: SQL leaked into metric_text")
        try:
            actual = compile_example(row)
            if row.get('compiled_outcome') is not None and row['compiled_outcome'] != actual:
                errors.append(f"{row.get('id')}: compiled outcome changed")
        except (ValueError, KeyError, TypeError) as exc:
            errors.append(f"{row.get('id')}: compiler rejected example ({type(exc).__name__}: {exc})")

    for row in distractors:
        code = row.get("code") or ""
        if row.get("role") != "retrieval_distractor":
            errors.append(f"distractor {row.get('id')} bad role")
        if code in RESERVED_CODES:
            errors.append(f"distractor code conflicts reserved: {code}")
        if code in dist_codes:
            errors.append(f"duplicate distractor code {code}")
        dist_codes.add(code)
        if row.get("executable") is not False:
            errors.append(f"{code}: executable must be false")
        if row.get("allowed_modes"):
            errors.append(f"{code}: allowed_modes must be empty")
        definition = row.get("definition") or ""
        if "本PoC不可查询" not in definition:
            errors.append(f"{code}: definition must mark non-queryable")
        if "SELECT" in definition.upper() or " FROM " in definition.upper():
            errors.append(f"{code}: SQL-like text in definition")
        if row.get("reviewed"):
            reviewed_dist += 1

    # A generated boolean cannot establish human review. Quality gates above
    # execute real validation; separately record manual review when it happens.

    # Matrix coverage: each metric×mode at least once.
    covered = {(e["draft"]["metric_codes"][0], e["draft"].get("query_mode") or "scalar") for e in examples
               if e.get("draft", {}).get("metric_codes")}
    for code in EXECUTABLE_CODES:
        for mode in MODES:
            if (code, mode) not in covered:
                errors.append(f"missing matrix cell {code}/{mode}")
    return errors


def write_manifest(examples, distractors):
    reviewed_dist = sum(1 for d in distractors if d.get("reviewed"))
    manifest = {
        "corpus_version": "retrieval-corpus-v2.1",
        "plan": "R4规模化语义检索数据Mock实施计划",
        "executable_codes": list(EXECUTABLE_CODES),
        "reserved_codes": list(RESERVED_CODES),
        "counts": {
            "examples": len(examples),
            "examples_reviewed": sum(1 for e in examples if e.get("reviewed")),
            "examples_compiler_checked": sum(1 for e in examples if e.get("compiled_outcome")),
            "distractors": len(distractors),
            "distractors_reviewed": reviewed_dist,
        },
        "notes": [
            "Distractors never enter Java semantic_query.",
            "Examples omit organization names and tenant IDs.",
            "Open HR employee dumps are not used as gold labels.",
        ],
        "generate": "python -m evals.build_retrieval_corpus --write",
        "validate": "python -m evals.build_retrieval_corpus --validate",
    }
    (CORPUS_DIR / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="Regenerate jsonl + manifest")
    parser.add_argument("--validate", action="store_true", help="Validate on-disk corpus")
    args = parser.parse_args(argv)
    if not args.write and not args.validate:
        args.validate = True

    if args.write:
        examples, distractors = example_rows(), distractor_rows()
        errors = validate(examples, distractors)
        if errors:
            print("REFUSING WRITE:", *errors, sep="\n- ")
            return 1
        write_jsonl(CORPUS_DIR / "examples_v2.jsonl", examples)
        write_jsonl(CORPUS_DIR / "metrics_distractors.jsonl", distractors)
        manifest = write_manifest(examples, distractors)
        print(json.dumps({"wrote": True, "manifest": manifest}, ensure_ascii=False, indent=2))

    if args.validate:
        examples = load_jsonl(CORPUS_DIR / "examples_v2.jsonl")
        distractors = load_jsonl(CORPUS_DIR / "metrics_distractors.jsonl")
        errors = validate(examples, distractors)
        if errors:
            print("VALIDATION FAILED:", *errors, sep="\n- ")
            return 1
        print(json.dumps({"ok": True, "examples": len(examples), "distractors": len(distractors)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
