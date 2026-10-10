# Baseline: retrieval-scale-v1 / dev (2026-10-10)

Historical diagnostic only. The subsequent v2.1 audit found 31/36 examples failed
the actual compiler contract, 28/42 dev questions duplicated examples, and null
mode incorrectly received an automatic example hit. Keep these original figures
for traceability; see [evaluation notes](../../evals/README.md) for corrected scores
and the separate 14-question non-overlap subset. Do not compare versions as an uplift.

Source report (local, gitignored): `docs/evaluation-runs/retrieval-scale-20261010T083322005499Z/report.json`

| Mode | Metric Hit@1 | Metric Hit@2 | Top-1 distractor rate | Example Hit@2 | Median ms | Candidates |
|---|---:|---:|---:|---:|---:|---:|
| lexical | 0.43 | 0.67 | 0.55 | 0.95 | 1.8 | 99 |
| hybrid | 0.50 | 0.67 | 0.48 | 0.93 | 18.4 | 99 |

Corpus: `retrieval-corpus-v2` — 36 examples, 60 distractors. Executable metrics in catalog: 3.

Do not rewrite these as end-to-end ask accuracy.
