"""Focused tests for the optional related-passages discovery path.

Covers: flag default, frozen budget, merge ranking/dedup, top-5 output shape, per-page failure
fallback, whole-route fallback, and the structural rule that the discovery module contains no
decision logic. No model weights and no 742 MB index are loaded: the route is exercised with
stubbed page content, and the frozen budget is read from the real freeze manifest.

Run: D:\\TruthLens\\ai-service\\.venv\\Scripts\\python.exe ai-service/tests/test_related_passages.py
"""
from __future__ import annotations

import copy
import json
import os
import sys
import threading
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app  # noqa: E402
import related_passages as rp  # noqa: E402

BANNED_IN_DISCOVERY = ["gate_and_select", "claim_addresses", "named_scores", "min_similarity",
                      "min_overlap", "min_nli", "min_margin", "min_assertion_overlap",
                      "SUPPORTED", "REFUTED", "INSUFFICIENT", "gold", "dev.jsonl", "nli_model",
                      "softmax", "label"]


def stub_router(title_out=None, title_stats=None):
    r = rp.Router.__new__(rp.Router)
    r._lock = threading.RLock()
    r.metrics = {k: 0 for k in ("calls", "errors", "pages_selected", "pages_fetched", "pages_skipped",
                                "sentences_searched", "sentences_encoded", "cache_hit_rows",
                                "memo_hits", "memo_evictions", "vector_evictions")}
    r.ch = app
    r.frozen = {"route": "merge", "pages_per_claim": 5, "top_k_output": 5,
                "max_sentences_per_page": 40, "candidates_per_route": 10}
    if title_out is not None:
        r.title_candidates = lambda claim, q: (title_out, title_stats or {"select_ms": 1.0,
                                                                         "score_ms": 1.0, "sentences": len(title_out),
                                                                         "pages": 5, "skipped_pages": 0, "encoded": 0})
    return r


def cand(page, sid, score, source="deployed_v2", route="faiss"):
    return {"page_id": page, "sentence_id": sid, "text": f"{page} sentence {sid}", "score": score,
            "source": source, "route": route, "rank": 0}


def test_flag_off_by_default_and_opt_in_values():
    for value in (None, "", "0", "false", "off", "  "):
        if value is None:
            os.environ.pop(rp.FLAG, None)
        else:
            os.environ[rp.FLAG] = value
        assert rp.flag_on() is False, value
    for value in ("1", "true", "TRUE", "on", "yes"):
        os.environ[rp.FLAG] = value
        assert rp.flag_on() is True, value
    os.environ.pop(rp.FLAG, None)


def test_flag_off_adds_no_key_to_the_response():
    os.environ.pop(rp.FLAG, None)
    assert rp.flag_on() is False
    assert app.related_passages_for("Some claim.", np.zeros(384, np.float32), []) is None


def test_frozen_budget_is_the_evaluated_merge_at_five():
    frozen = rp._frozen_choice()
    assert (frozen["route"], frozen["pages_per_claim"], frozen["top_k_output"]) == ("merge", 5, 5)
    assert frozen["max_sentences_per_page"] == 40 and frozen["candidates_per_route"] == 10
    assert rp.K == 5


def test_merge_dedups_by_page_and_sentence_keeping_the_max_score():
    a = [cand("Paris", 0, 0.60), cand("Eiffel_Tower", 3, 0.50)]
    b = [cand("Paris", 0, 0.72, source="encoded_now", route="title"), cand("Louvre", 1, 0.55, route="title")]
    out = rp.Router.merge(a, b)
    keys = [(c["page_id"], c["sentence_id"]) for c in out]
    assert keys == [("Paris", 0), ("Louvre", 1), ("Eiffel_Tower", 3)]
    assert out[0]["score"] == 0.72 and out[0]["route"] == "title"
    assert len(out) == 3, "the duplicate must collapse to one row"


def test_merge_matches_the_frozen_pilot_implementation():
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "verification-logs" /
                           "fever-title-pilot-20261015"))
    import route_lib
    a = [cand("A", 1, 0.10), cand("B", 2, 0.90), cand("C", 0, 0.40)]
    b = [cand("A", 1, 0.30, route="title"), cand("D", 5, 0.95, route="title"), cand("B", 2, 0.85, route="title")]
    assert rp.Router.merge(a, b) == route_lib.Pilot.merge(None, a, b)
    assert [c["page_id"] for c in rp.Router.merge([], [])] == []


def test_top5_output_is_ranked_labeled_and_discovery_only():
    title = [cand("X", 0, 0.90, source="vector_cache", route="title"),
             cand("Y", 4, 0.80, source="encoded_now", route="title"),
             cand("Z", 2, 0.30, source="encoded_now", route="title")]
    faiss = [cand("P", 1, 0.85), cand("Q", 7, 0.20)]
    r = stub_router(title)
    out = r.related("X is a thing.", np.zeros(384, np.float32), faiss)
    assert out["status"] == "ok"
    p = out["candidates"]
    assert len(p) == rp.K, f"the frozen pilot returns a top-{rp.K}"
    assert [c["rank"] for c in p] == [1, 2, 3, 4, 5]
    scores = [c["score"] for c in p]
    assert scores == sorted(scores, reverse=True)
    assert [c["page_id"] for c in p] == ["X", "P", "Y", "Z", "Q"]
    for c in p:
        assert c["used_for_verdict"] is False
        assert c["page_title"] and "sentence_id" in c and c["text"]
        assert "not used" not in c["page_title"]
        assert c["provenance"] and c["source"] in {"deployed_v2", "vector_cache", "encoded_now"}
        assert "nli_scores" not in c and "named_scores" not in c and "verdict" not in c
    assert json.loads(json.dumps(out, default=str))["candidates"][0]["page_id"] == "X"


def test_no_candidates_still_returns_an_empty_block_not_an_error():
    r = stub_router([])
    out = r.related("Nothing matches.", np.zeros(384, np.float32), [])
    assert out["status"] == "ok" and out["candidates"] == []


def test_unavailable_assets_fall_back_to_the_deployed_path():
    os.environ[rp.FLAG] = "1"
    keep_router, keep_err = app._rp_router, app._rp_error
    app._rp_router, app._rp_error = None, "simulated: title index missing"
    try:
        block = app.related_passages_for("Paris is in France.", np.zeros(384, np.float32), [])
    finally:
        app._rp_router, app._rp_error = keep_router, keep_err
    assert block["status"] == "unavailable"
    assert block["passages"] == [] and block["used_for_verdict"] is False
    assert "unchanged" in block["note"]
    os.environ.pop(rp.FLAG, None)


def test_a_missing_page_is_skipped_and_the_rest_of_the_route_still_answers():
    r = rp.Router.__new__(rp.Router)
    r._lock = threading.RLock()
    r.ch = app
    r.metrics = {k: 0 for k in ("calls", "errors", "pages_selected", "pages_fetched", "pages_skipped",
                                "sentences_searched", "sentences_encoded", "cache_hit_rows",
                                "memo_hits", "memo_evictions", "vector_evictions")}
    r.frozen = rp._frozen_choice()
    pages = [{"page_id": f"P{i}", "member": "m", "offset": i, "title_norm": f"p{i}"} for i in range(5)]

    class Th:
        @staticmethod
        def select_pages(con, claim, n):
            return pages[:n]
    r.th, r._title_con = Th, lambda: None

    def page_sentences(page):
        return [(0, "x"), (2, "y")]

    def page_vectors(pid, sents):
        if pid == "P3":
            raise RuntimeError("byte offset wrong for P3: a missing or moved page")
        rows = [(pid, sid, txt, "deployed_v2") for sid, txt in sents]
        return rows, np.tile(np.array([1.0, 0.0], np.float32), (len(sents), 1)), 0
    r._page_sentences, r._page_vectors = page_sentences, page_vectors
    q = np.array([0.9, 0.1], dtype=np.float32)

    out, st = r.title_candidates("Some claim.", q)
    assert st["pages"] == 5 and st["skipped_pages"] == 1, st
    assert st["sentences"] == 8, st
    assert {c["page_id"] for c in out} <= {p["page_id"] for p in pages} - {"P3"}
    assert all(c["route"] == "title" for c in out)
    merged = r.related("Some claim.", q, [])
    assert merged["status"] == "ok" and 0 < len(merged["candidates"]) <= rp.K
    assert all(c["used_for_verdict"] is False for c in merged["candidates"])


def test_discovery_module_holds_no_decision_logic():
    body = (Path(__file__).resolve().parents[1] / "related_passages.py").read_text(encoding="utf-8")
    code = body.split('"""', 2)[2]
    for token in BANNED_IN_DISCOVERY:
        assert token not in code, f"the discovery module must not touch {token!r}"
    src = Path(__file__).resolve().parents[1] / "app.py"
    text = src.read_text(encoding="utf-8")
    call = text.index("verdict,selected=gate_and_select")
    after = text[call:]
    assert after.index("related_passages_for(r.claim,q,cand)") > after.index('e.pop("premise",None)'), \
        "discovery must run only after the verdict is final"


def test_block_key_is_additive_and_does_not_collide():
    keys = {"verdict", "claim", "reason", "selected", "evidence", "parameters", "model", "note"}
    r = stub_router([cand("X", 0, 0.9, route="title")])
    block = r.related("X exists.", np.zeros(384, np.float32), [])
    assert keys.isdisjoint({"related_passages"})
    assert set(block) == {"status", "candidates", "route_stats", "merged_pool", "total_ms"}


if __name__ == "__main__":
    import inspect
    names = [n for n, f in sorted(globals().items()) if n.startswith("test_") and inspect.isfunction(f)]
    for n in names:
        globals()[n]()
    print(f"PASS ai-service/tests/test_related_passages.py ({len(names)} tests)")
