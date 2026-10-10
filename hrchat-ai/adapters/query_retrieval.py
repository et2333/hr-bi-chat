"""Permission-scoped metadata and QueryDraft retrieval; no SQL or answer cache.

The current authorized Java catalog is the sole source of executable metrics.
Distractor metrics enlarge retrieval candidates only and never become executable.
Document embeddings may be cached, but every search builds its candidate set anew.
"""
from __future__ import annotations

from collections import Counter, OrderedDict
from functools import lru_cache
import hashlib
import json
import math
import os
from pathlib import Path
import re
import threading
import time

MODEL_ID = "BAAI/bge-small-zh-v1.5"
MODEL_DIR = Path(__file__).resolve().parents[1] / ".models" / "bge-small-zh-v1.5"
CORPUS_DIR = Path(__file__).resolve().parent / "retrieval_corpus"
CORPUS_VERSION = "retrieval-corpus-v2.1"


def _load_jsonl(path: Path):
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


@lru_cache(maxsize=1)
def load_corpus():
    examples_path = CORPUS_DIR / "examples_v2.jsonl"
    distractors_path = CORPUS_DIR / "metrics_distractors.jsonl"
    if not examples_path.is_file():
        raise FileNotFoundError(f"Missing retrieval examples: {examples_path}")
    if not distractors_path.is_file():
        raise FileNotFoundError(f"Missing retrieval distractors: {distractors_path}")
    examples = _load_jsonl(examples_path)
    distractors = _load_jsonl(distractors_path)
    if not examples:
        raise ValueError("retrieval examples_v2.jsonl is empty")
    return {"examples": examples, "distractors": distractors, "version": CORPUS_VERSION}


def attach_prompt_distractors(catalog):
    """Evaluation-only: expand the *prompt* metric list with retrieval distractors.

    Compile/validate still use catalog['metrics'] (Java-authoritative executables).
    """
    corpus = load_corpus()
    prompt = []
    for m in catalog["metrics"]:
        prompt.append({k: m[k] for k in ("code", "name", "aliases", "definition", "allowed_modes", "requires_period")
                       if k in m})
    known = {m["code"] for m in prompt}
    for row in corpus["distractors"]:
        if row["code"] in known:
            continue
        prompt.append({
            "code": row["code"],
            "name": row["name"],
            "aliases": row.get("aliases") or [],
            "definition": row["definition"],
            "allowed_modes": [],
            "requires_period": True,
        })
    catalog["prompt_metrics"] = prompt
    catalog["prompt_distractors"] = True
    return catalog


def documents(catalog, *, include_distractors=False):
    """Build per-request docs: live catalog metrics ∪ distractors ∪ matching examples."""
    corpus = load_corpus()
    metrics = {m["code"]: m for m in catalog["metrics"]}
    docs = []
    for m in metrics.values():
        content = {k: m[k] for k in ("code", "name", "aliases", "definition", "version", "allowed_modes", "requires_period")}
        executable = bool(m["allowed_modes"])
        content["executable"] = executable
        content["role"] = "executable" if executable else "catalog_visible_unsupported"
        docs.append({"id": "metric:" + m["code"], "kind": "metadata", "code": m["code"],
                     "version": m["version"], "executable": executable,
                     "text": json.dumps(content, ensure_ascii=False), "content": content})
    for row in corpus["distractors"] if include_distractors else []:
        code = row["code"]
        if code in metrics:
            # Never shadow an authorized catalog metric with a distractor.
            continue
        content = {
            "code": code,
            "name": row["name"],
            "aliases": row.get("aliases") or [],
            "definition": row["definition"],
            "version": 0,
            "allowed_modes": [],
            "requires_period": False,
            "executable": False,
            "role": "retrieval_distractor",
        }
        docs.append({"id": "distractor:" + code, "kind": "metadata", "code": code,
                     "version": 0, "executable": False,
                     "text": json.dumps(content, ensure_ascii=False), "content": content})
    for row in corpus["examples"]:
        draft = row["draft"]
        codes = draft.get("metric_codes") or []
        if len(codes) != 1 or codes[0] not in metrics:
            continue
        mode = draft.get("query_mode") or "scalar"
        if mode not in metrics[codes[0]]["allowed_modes"]:
            continue
        docs.append({"id": "example:" + row["id"], "kind": "example", "code": codes[0],
                     "version": metrics[codes[0]]["version"], "executable": True,
                     "text": row["question"],
                     "content": {"question": row["question"], "draft": draft}})
    return docs


def tokens(text):
    words = re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]", text.lower())
    chinese = re.findall(r"[\u4e00-\u9fff]+", text)
    return words + [part[i:i + 2] for part in chinese for i in range(len(part) - 1)]


def lexical_scores(question, docs):
    terms = [Counter(tokens(d["text"])) for d in docs]
    df = Counter(t for t in (tok for row in terms for tok in row))
    average = sum(sum(row.values()) for row in terms) / max(1, len(terms))
    query = set(tokens(question))
    return [sum(math.log(1 + (len(docs) - df[t] + .5) / (df[t] + .5)) *
                (row[t] * 2.2) / (row[t] + 1.2 * (.25 + .75 * sum(row.values()) / max(average, 1)))
                for t in query if row[t]) for row in terms]


class LocalEncoder:
    def __init__(self, path=None):
        self.path = Path(path or os.getenv("HRCHAT_EMBEDDING_DIR") or MODEL_DIR)
        if not (self.path / "model.safetensors").is_file():
            raise FileNotFoundError("Run python -m adapters.prepare_embedding first")
        import torch
        from sentence_transformers import SentenceTransformer
        torch.set_num_threads(2)
        self.model = SentenceTransformer(str(self.path), device="cpu", local_files_only=True,
                                         trust_remote_code=False, model_kwargs={"use_safetensors": True})
        self.cache = OrderedDict()
        self.lock = threading.Lock()

    def encode(self, texts):
        # Bounded, content-addressed in-process cache; no cross-user candidate index.
        with self.lock:
            missing = list(dict.fromkeys(t for t in texts if t not in self.cache))
            if missing:
                vectors = self.model.encode(missing, normalize_embeddings=True, show_progress_bar=False)
                for text, vector in zip(missing, vectors):
                    self.cache[text] = vector
            result = [self.cache[t] for t in texts]
            for text in texts:
                self.cache.move_to_end(text)
            while len(self.cache) > 512:
                self.cache.popitem(last=False)
            return result


@lru_cache(maxsize=1)
def encoder():
    return LocalEncoder()


# One RRF step (1/61≈0.0164). Enough to break near-ties toward executables without
# erasing a large lexical/dense gap when a distractor is clearly more similar.
EXECUTABLE_SCORE_BOOST = 1.0 / 61


def rank(question, docs, mode, embedder=None, *, prefer_executable=False):
    if not docs:
        return []
    lexical = lexical_scores(question, docs)
    lexical_order = sorted(range(len(docs)), key=lambda i: (-lexical[i], docs[i]["id"]))
    if mode == "lexical":
        ranked = [(docs[i], lexical[i]) for i in lexical_order if lexical[i] > 0]
    else:
        vectors = (embedder or encoder()).encode(["为这个句子生成表示以用于检索相关文章：" + question] + [d["text"] for d in docs])
        dense = [float(sum(a * b for a, b in zip(vectors[0], v))) for v in vectors[1:]]
        dense_order = sorted(range(len(docs)), key=lambda i: (-dense[i], docs[i]["id"]))
        scores = Counter()
        for ordering in (dense_order, [i for i in lexical_order if lexical[i] > 0]):
            for position, index in enumerate(ordering):
                scores[index] += 1 / (60 + position + 1)
        ranked = [(docs[i], scores[i]) for i in sorted(scores, key=lambda i: (-scores[i], docs[i]["id"]))]
    if prefer_executable and ranked:
        # Lexical BM25 scores are O(1..20); RRF scores are O(0.01). Scale the boost accordingly.
        peak = max(score for _, score in ranked) or 1.0
        boost = EXECUTABLE_SCORE_BOOST if mode == "hybrid" else max(0.5, 0.08 * peak)
        ranked = sorted(
            ((doc, score + (boost if doc.get("executable") else 0.0)) for doc, score in ranked),
            key=lambda item: (-item[1], item[0]["id"]),
        )
    return ranked


def retrieve(question, catalog, *, mode=None, embedder=None, include_distractors=False,
             prefer_executable=None):
    mode = mode or os.getenv("HRCHAT_RAG_MODE", "off")
    if mode not in {"off", "lexical", "hybrid"}:
        raise ValueError("HRCHAT_RAG_MODE must be off, lexical or hybrid")
    if prefer_executable is None:
        prefer_executable = include_distractors
    started = time.perf_counter()
    fingerprint = hashlib.sha256(json.dumps(catalog, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    evidence = {"mode": mode, "corpus_version": CORPUS_VERSION, "catalog_fingerprint": fingerprint,
                "candidate_count": 0, "executable_metric_count": 0, "distractor_count": 0,
                "include_distractors": include_distractors,
                "prefer_executable": prefer_executable,
                "model": MODEL_ID if mode == "hybrid" else None,
                "selected": [], "ranked_metadata_codes": [],
                "status": "disabled" if mode == "off" else "ok"}
    if mode == "off":
        evidence["elapsed_ms"] = round((time.perf_counter() - started) * 1000, 2)
        return {}, evidence
    try:
        docs = documents(catalog, include_distractors=include_distractors)
        evidence.update(candidate_count=len(docs),
            executable_metric_count=sum(d["kind"] == "metadata" and d.get("executable", False) for d in docs),
            distractor_count=sum(d["content"].get("role") == "retrieval_distractor" for d in docs),
            corpus_fingerprint=hashlib.sha256(json.dumps(docs, ensure_ascii=False, sort_keys=True).encode()).hexdigest())
        metadata_ranked = rank(question, [d for d in docs if d["kind"] == "metadata"], mode, embedder,
                               prefer_executable=prefer_executable)
        evidence["ranked_metadata_codes"] = [
            {"code": d["code"], "executable": bool(d.get("executable")), "score": score}
            for d, score in metadata_ranked
        ]
        selected = list(metadata_ranked[:2])
        selected.extend(rank(question, [d for d in docs if d["kind"] == "example"], mode, embedder,
                             prefer_executable=False)[:2])
        evidence["selected"] = [{"id": d["id"], "code": d["code"], "version": d["version"],
                                 "executable": d.get("executable", True), "score": score}
                                for d, score in selected]
        # Planning context may show distractor definitions for disambiguation, but
        # compile/validate still use catalog.metrics only — never distractor codes.
        context = {"metadata": [d["content"] for d, _ in selected if d["kind"] == "metadata"],
                   "examples": [d["content"] for d, _ in selected if d["kind"] == "example"]}
    except (OSError, ImportError, RuntimeError, ValueError):
        # Keep the full authoritative catalog; a missing model never invents embeddings.
        context = {}
        evidence.update(status="fallback_full_catalog", reason="local_retrieval_unavailable")
    evidence["elapsed_ms"] = round((time.perf_counter() - started) * 1000, 2)
    return context, evidence
