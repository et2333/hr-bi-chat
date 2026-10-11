"""Freeze new authored expressions before model runs; never overwrite a version."""
from copy import deepcopy
import json
import re
import unicodedata

from adapters.query_retrieval import load_corpus
from evals.dataset import RAG_TASK_DATASET, MODE_DATASET, load_dataset, sha_text


# Explicit date/org controls come from literal-seed oracle anchors, not the model.
# Phrases intentionally do not supply dates that conflict with those controls.
QUERIES = [
    ("01", "hr-modes-v1-01", "在我选定的月份里，研发中心每个月实际在岗的员工规模怎么变化？"),
    ("02", "hr-modes-v1-02", "按我选的日期范围，把研发中心每月真正报到加入的同事人数列出来。"),
    ("03", "hr-modes-v1-03", "所选时间里，研发中心每个月离开公司的员工分别有几位？"),
    ("04", "hr-modes-v1-06", "用选好的日期，研发中心现有员工在各个部门分别有多少？不要只给一个总数。"),
    ("05", "hr-modes-v1-07", "所选期间实际加入研发中心的同事，分别落在哪个部门，各有多少人？"),
    ("06", "hr-modes-v1-11", "用已选日期看看研发二部仍在职的员工，请逐个人列一张名单。"),
]
STOPS = [
    ("07", "clarify", "研发中心离开公司的同事一共有几位？统计哪段时间我还没确定。"),
    ("08", "clarify", "研发中心报到加入的同事有多少人？先问我需要查哪个时间段。"),
    ("09", "clarify", "研发中心各部门做个对照，不过我还没决定比较哪个指标。"),
    ("10", "unsupported", "研发中心八月份员工的薪资，给我算平均值。"),
    ("11", "unsupported", "研发中心八月份主动辞职的人占全部离职的比例是多少？"),
    ("12", "unsupported", "研发中心九月新发出的录用通知有多少份？不统计已报到的人。"),
]


def normalized(text):
    return re.sub(r"[^\w\u4e00-\u9fff]", "", unicodedata.normalize("NFKC", text).lower())


def questions(document):
    if isinstance(document, dict):
        for key, value in document.items():
            if key == "question" and isinstance(value, str):
                yield value
            else:
                yield from questions(value)
    elif isinstance(document, list):
        for row in document:
            yield from questions(row)


def novelty_audit(cases):
    corpus = load_corpus()
    previous = set(normalized(q) for q in questions(corpus))
    for path in RAG_TASK_DATASET.parent.glob("*/cases.json"):
        if path.parent != RAG_TASK_DATASET:
            previous.update(normalized(q) for q in questions(json.loads(path.read_text(encoding="utf-8"))))
    authored = [normalized(t["question"]) for c in cases for t in c["turns"]]
    return {"normalized_exact_overlap": sum(q in previous for q in authored),
            "duplicate_new_questions": len(authored) - len(set(authored)),
            "previous_question_count": len(previous),
            "note": "Exact novelty only; semantic independence and real-user representativeness are not established."}


def build():
    if (RAG_TASK_DATASET / "manifest.json").exists():
        raise ValueError("Version already exists; never regenerate scored expectations")
    parent_manifest, anchors = load_dataset(MODE_DATASET)
    indexed = {c["case_id"]: c for c in anchors}
    cases = []
    for number, anchor, question in QUERIES:
        case = deepcopy(indexed[anchor])
        case.update(case_id="rag-unseen-v1-" + number, split="frozen",
                    group_id="new_" + case["scene"], template_id="new_" + case["scene"],
                    origin="assistant_authored", human_review="pending", oracle_anchor=anchor)
        case["turns"][0]["question"] = question
        cases.append(case)
    for number, scene, question in STOPS:
        cases.append({"case_id": "rag-unseen-v1-" + number, "group_id": "new_" + scene,
            "template_id": "new_" + scene, "scene": scene, "split": "frozen",
            "data_version": parent_manifest["data_version"], "as_of_date": parent_manifest["as_of_date"],
            "identity_fixture": "hr01", "session_owner": "hr01", "origin": "assistant_authored",
            "human_review": "pending", "turns": [{"question": question,
                "context_override": {"orgId": "2", "includeChildren": True},
                "expected": {"statuses": ["CLARIFYING" if scene == "clarify" else "UNSUPPORTED"],
                    "no_data": True, "no_execution": True, "require_no_query_trace": True}}]})
    audit = novelty_audit(cases)
    if audit["normalized_exact_overlap"] or audit["duplicate_new_questions"]:
        raise ValueError("New expressions overlap existing material")
    RAG_TASK_DATASET.mkdir(parents=True, exist_ok=True)
    path = RAG_TASK_DATASET / "cases.json"
    path.write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {**parent_manifest, "dataset_version": RAG_TASK_DATASET.name,
        "cases_sha256": sha_text(path), "case_count": len(cases), "turn_count": len(cases),
        "origin": "assistant_authored_before_first_model_run", "human_review": "pending",
        "novelty_audit": audit, "oracle_anchor_cases_sha256": parent_manifest["cases_sha256"],
        "scope": "Unseen-expression task acceptance: six executable queries, three clarifications, three unsupported queries; ordinary catalog, no injected distractors",
        "limitations": ["Synthetic expressions, not independently collected user traffic",
                       "Query result oracle reuses literal-seed anchors; language is new, business tasks are not",
                       "Date/org controls are explicit UI inputs, not natural-language parsing evidence",
                       "No tuning, rewriting expectations, or adding examples after observing scored outcomes"]}
    (RAG_TASK_DATASET / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"dataset": str(RAG_TASK_DATASET), "audit": audit, "cases": len(cases), "real_calls": 0}, ensure_ascii=False))


if __name__ == "__main__":
    build()
