# Retrieval corpus v2 (R4)

Permission-scoped planning still uses the Java catalog as the only executable
metric authority. This directory only supplies **retrieval Context**:

| File | Role | Enters Java execute path? |
|---|---|---|
| `metrics_distractors.jsonl` | `retrieval_distractor` — large fake semantic objects | **No** |
| `examples_v2.jsonl` | `example_gold` — verified question→QueryDraft few-shots | Only if draft metric is already in the live catalog |
| `manifest.json` | Version, counts, reserved codes | — |

## First-period targets

- Executable metrics in demos: still `headcount` / `hire_count` / `leave_count`
- Distractors: 40–80
- Examples: ≥36 (3 metrics × 4 modes × ≥3 phrasings)
- Eval set: `evals/datasets/retrieval-scale-v1/` (separate from this corpus)

## Validate

From `hrchat-ai`:

```powershell
.\.venv\Scripts\python.exe -m evals.build_retrieval_corpus --validate
```

Regenerate authored drafts (overwrites jsonl; review flags reset for new rows only when using `--write`):

```powershell
.\.venv\Scripts\python.exe -m evals.build_retrieval_corpus --write
```

## Interview / resume framing

This is a **demo-tenant mock**: distractors simulate a large semantic catalog;
spot-checked examples teach planning. Do not claim open HR employee dumps or
bureau statistics as executable gold labels.
