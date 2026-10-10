# Retrieval corpus v2.1 (R4, reviewed implementation)

Permission-scoped planning still uses the Java catalog as the only executable
metric authority. This directory only supplies **retrieval Context**:

| File | Role | Enters Java execute path? |
|---|---|---|
| `metrics_distractors.jsonl` | `retrieval_distractor` — large fake semantic objects | **No** |
| `examples_v2.jsonl` | `example_gold` — compiler-checked condition extraction examples | Only if draft metric/mode is executable in the live catalog |
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

Normal queries use only the live authorized catalog and applicable examples.
The 60 synthetic distractors are enabled only by the scale evaluator through
`include_distractors=True`; they are not injected into the default demo.
Visible catalog entries with no allowed query mode are marked non-executable.

Regenerate authored drafts (overwrites jsonl; does not fabricate human review):

```powershell
.\.venv\Scripts\python.exe -m evals.build_retrieval_corpus --write
```

## Interview / resume framing

This is a **demo-tenant mock**: distractors simulate a large semantic catalog;
compiler-checked examples teach condition extraction. Do not claim open HR employee dumps or
bureau statistics as executable gold labels.

`compiled_outcome` records the real compiler result. Missing or unresolved periods
can correctly produce clarification; examples do not invent a default period.
All 36 current examples pass this contract check. Human review is **not recorded**;
the old generator's automatic `reviewed=true` flags did not establish manual review.

The scale development set has 28/42 exact question overlaps with this corpus.
Its report separately scores the remaining 14 questions. The older 18-question
set now overlaps on 13 questions: historical v1 results remain valid for that
historical corpus, but reruns with v2.1 are in-sample diagnostics, not independent
generalization evidence. The 20 holdout questions have not been scored in this review.
