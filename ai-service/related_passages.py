"""Optional candidate-evidence discovery for /v1/verify (FEVER page-title retrieval pilot).

What this is
  verification-logs/fever-title-pilot-20261015 measured, on 400 unseen FEVER claims, exact
  annotated gold-evidence recall@5 of 110/400 for the deployed FAISS path and 258/400 for a merge
  of that path with a page-title search route (title-index.sqlite3 -> select_pages -> page
  sentences from wiki-pages.zip -> cosine against the claim vector). This module serves that
  merge ranking as DISCOVERY OUTPUT only.

What this is not
  It never enters the verdict. app.verify() finishes the deployed gate first and calls
  related() afterwards with a copy of the already-scored FAISS candidates; the returned passages
  get no NLI score and no gate. Nothing here reads a FEVER annotation, gold page, gold sentence id
  or label, and no decision threshold is defined here.

The frozen budget (merge@N=5, 40 sentences/page, 10 candidates/route, selector caps) is read from
freeze-manifest.json and asserted at load, so a silent retune fails loudly instead of showing up
as a differently-shaped result.

Caches are bounded two ways: an in-RAM whole-page memo (LRU, PAGE_MEMO_MAX_PAGES) and a persistent
sentence-vector cache under storage/related-passages/ (FIFO, VECTOR_CACHE_MAX_ROWS). The pilot's
own measurement used an unbounded in-process memo; that is deliberately not reproduced here.
"""
from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import sys
import threading
import time
import zipfile
from collections import OrderedDict
from pathlib import Path

import numpy as np

ROOT = Path(os.getenv("TRUTHLENS_ROOT", Path(__file__).resolve().parents[1]))
PILOT = ROOT / "verification-logs" / "fever-title-pilot-20261015"
CORPUS_TOOLS = ROOT / "verification-logs" / "srs-fever-corpus-20261015"
TITLE_DB = Path(os.getenv("TRUTHLENS_TITLE_DB", str(PILOT / "title-index.sqlite3")))
WIKI_ZIP = Path(os.getenv("TRUTHLENS_WIKI_ZIP",
                         str(ROOT / "models" / "Fever" / "truthlens_fever" / "raw" / "wiki-pages.zip")))
DEPLOYED_META = ROOT / "models" / "Fever" / "truthlens_fever_iteration2" / "evidence_metadata_v2.csv"
CACHE_DB = Path(os.getenv("TRUTHLENS_RELATED_CACHE",
                          str(ROOT / "storage" / "related-passages" / "sentence-vectors.sqlite3")))

FLAG = "TRUTHLENS_RELATED_PASSAGES"
K = 5                        # the frozen pilot reports a top-5; not configurable on purpose
PAGE_MEMO_MAX_PAGES = int(os.getenv("TRUTHLENS_RELATED_MEMO_PAGES", "256"))
VECTOR_CACHE_MAX_ROWS = int(os.getenv("TRUTHLENS_RELATED_VECTOR_ROWS", "50000"))
VECTOR_CACHE_EVICT_ROWS = int(os.getenv("TRUTHLENS_RELATED_VECTOR_EVICT", "10000"))
ZIP_HANDLES_MAX = 4


def flag_on() -> bool:
    return os.getenv(FLAG, "").strip().lower() in {"1", "true", "on", "yes"}


def _frozen_choice() -> dict:
    mf = json.loads((PILOT / "freeze-manifest.json").read_text(encoding="utf-8"))
    route, n = mf["frozen_route"], int(mf["frozen_n"])
    rc, sc = mf["route_constants"], mf["selector_constants"]
    if route != "merge" or n != K:
        raise RuntimeError(f"freeze manifest selects {route}@N={n}; this module implements merge@N=5")
    for name in ("title_helpers.py", "route_lib.py"):
        import hashlib
        digest = hashlib.sha256((PILOT / name).read_bytes()).hexdigest()
        if digest != mf["files"][name]:
            raise RuntimeError(f"frozen {name} no longer matches the freeze manifest")
    return {"route": route, "pages_per_claim": n, "top_k_output": K,
            "max_sentences_per_page": int(rc["MAX_SENTENCES_PER_PAGE"]),
            "candidates_per_route": int(rc["TOPK"]), "candidate_cap": int(sc["CANDIDATE_CAP"]),
            "span_row_limit": int(sc["SPAN_ROW_LIMIT"]),
            "selector_sha256": mf["files"]["title_helpers.py"]}


class Router:
    """Title index + wiki page archive + bounded sentence-vector cache, shared by all requests."""

    def __init__(self) -> None:
        for p in (str(CORPUS_TOOLS), str(PILOT)):
            if p not in sys.path:
                sys.path.insert(0, p)
        import corpus_helpers as ch  # noqa: E402  verbatim deployed text helpers
        import title_helpers as th   # noqa: E402  the frozen selector, not re-implemented here
        self.ch, self.th = ch, th

        self.frozen = _frozen_choice()
        self.stats = json.loads((PILOT / "title-index-stats.json").read_text(encoding="utf-8"))
        if not TITLE_DB.exists():
            raise RuntimeError(f"missing title index: {TITLE_DB}")
        if not WIKI_ZIP.exists():
            raise RuntimeError(f"missing page archive: {WIKI_ZIP}")
        if WIKI_ZIP.stat().st_size != int(self.stats["source_zip_bytes"]):
            raise RuntimeError("page archive size differs from the archive the index was built "
                               "from; its byte offsets would be meaningless")

        self._lock = threading.RLock()
        self._local = threading.local()
        self._memo: OrderedDict[str, list] = OrderedDict()
        self._seq_lock = threading.Lock()
        self._seq = 0

        t = time.time()
        self._title_con()
        self._zip_con()
        CACHE_DB.parent.mkdir(parents=True, exist_ok=True)
        self._cache_con()
        self._check_cache_identity()
        self._seq = int(self._cache_con().execute("SELECT COALESCE(MAX(seq),0) FROM sent").fetchone()[0] or 0)
        self.load_s = {"assets_s": round(time.time() - t, 2)}

        t = time.time()
        import pandas as pd
        dm = pd.read_csv(DEPLOYED_META, usecols=["page_id", "sentence_id"])
        self._sids = [int(x) for x in dm.sentence_id]
        self._by_page: dict[str, dict[int, int]] = {}
        for i, pid in enumerate(dm.page_id):
            self._by_page.setdefault(str(pid), {})[self._sids[i]] = i
        self.load_s["metadata_s"] = round(time.time() - t, 2)
        self.metrics = {"calls": 0, "errors": 0, "pages_selected": 0, "pages_fetched": 0,
                        "pages_skipped": 0, "sentences_searched": 0, "sentences_encoded": 0,
                        "cache_hit_rows": 0, "memo_hits": 0, "memo_evictions": 0,
                        "vector_evictions": 0}

    # ------------------------------------------------------------------ per-thread handles
    def _title_con(self):
        con = getattr(self._local, "title", None)
        if con is None:
            con = sqlite3.connect(f"file:{TITLE_DB.as_posix()}?mode=ro", uri=True)
            con.execute("PRAGMA mmap_size=268435456")
            con.execute("PRAGMA cache_size=-65536")
            self._local.title = con
        return con

    def _cache_con(self):
        con = getattr(self._local, "cache", None)
        if con is None:
            con = sqlite3.connect(str(CACHE_DB))
            con.execute("PRAGMA synchronous=NORMAL")
            con.execute("CREATE TABLE IF NOT EXISTS sent(page_id TEXT, sentence_id INT, text TEXT,"
                        " vec BLOB, seq INTEGER, PRIMARY KEY(page_id, sentence_id)) WITHOUT ROWID")
            con.execute("CREATE INDEX IF NOT EXISTS sent_seq ON sent(seq)")
            con.execute("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
            self._local.cache = con
        return con

    def _zip_con(self):
        z = getattr(self._local, "zip", None)
        if z is None:
            z = self._local.zip = zipfile.ZipFile(str(WIKI_ZIP))
            self._local.handles = {}
        return z

    def _check_cache_identity(self) -> None:
        con = self._cache_con()
        mine = {"source_zip_md5": str(self.stats["source_zip_md5"]), "encoder": "minilm_encoder",
                "max_sentences_per_page": str(self.frozen["max_sentences_per_page"]), "dim": "384",
                "embed_text": "page_id.replace('_',' ')+'. '+sentence"}
        stored = {k: str(v) for k, v in con.execute("SELECT k, v FROM meta").fetchall()}
        if not stored:
            con.executemany("INSERT OR REPLACE INTO meta VALUES (?,?)", list(mine.items()))
            con.commit()
            return
        bad = {k: (stored.get(k), v) for k, v in mine.items() if stored.get(k) != v}
        if bad:
            raise RuntimeError(f"sentence-vector cache built under different conditions: {bad}")

    def attach(self, encoder, index) -> None:
        """Reuse the encoder and FAISS index the FEVER endpoint already holds. Idempotent."""
        with self._lock:
            if getattr(self, "index", None) is index and getattr(self, "enc", None) is encoder:
                return
            self.enc = encoder
            self.index = index
            self._page_vecs: dict[int, np.ndarray] = {}

    def close(self) -> None:
        with self._lock:
            self._memo.clear()
        for attr in ("title", "cache"):
            con = getattr(self._local, attr, None)
            if con is not None:
                con.close()
                setattr(self._local, attr, None)

    # ------------------------------------------------------------------ page content
    def _fetch_page(self, member: str, offset: int, page_id: str) -> list:
        z = self._zip_con()
        handles = self._local.handles
        f = handles.get(member)
        if f is None:
            if len(handles) >= ZIP_HANDLES_MAX:
                for key in list(handles)[:2]:
                    handles.pop(key).close()
            f = handles[member] = z.open(member)
        f.seek(offset)
        rec = json.loads(f.readline().decode("utf-8", errors="replace"))
        if str(rec.get("id")) != page_id:
            raise RuntimeError(f"byte offset wrong for {page_id} in {member}")
        return self.ch.page_sentences(str(rec.get("lines", "")))[:self.frozen["max_sentences_per_page"]]

    def _page_sentences(self, page: dict) -> list:
        pid = page["page_id"]
        with self._lock:
            hit = self._memo.get(pid)
            if hit is not None:
                self._memo.move_to_end(pid)
                self.metrics["memo_hits"] += 1
                return hit
        sents = self._fetch_page(page["member"], page["offset"], pid)
        with self._lock:
            self.metrics["pages_fetched"] += 1
            self._memo[pid] = sents
            self._memo.move_to_end(pid)
            while len(self._memo) > PAGE_MEMO_MAX_PAGES:
                self._memo.popitem(last=False)
                self.metrics["memo_evictions"] += 1
        return sents

    def _deployed_vector(self, row: int) -> np.ndarray:
        with self._lock:
            v = self._page_vecs.get(row)
            if v is not None:
                return v
            v = np.asarray(self.index.reconstruct(int(row)), dtype=np.float32)
            if len(self._page_vecs) > PAGE_MEMO_MAX_PAGES * 40:
                self._page_vecs.clear()
            self._page_vecs[row] = v
            return v

    def _page_vectors(self, page_id: str, sents: list) -> tuple[list, np.ndarray, int]:
        """(rows, vectors, newly_encoded) with rows[i] and vectors[i] describing the same sentence.

        rows = [(page_id, sentence_id, text, source)] with source in {deployed_v2, vector_cache,
        encoded_now}: the deployed index supplies a vector wherever that (page, sentence) exists
        there, else the bounded cache, else the encoder writes it into the cache.
        """
        items: list[tuple] = []
        todo: list[tuple] = []
        deployed = self._by_page.get(page_id, {})
        cache = self._cache_con()
        with self._lock:
            for sid, txt in sents:
                sid = int(sid)
                row = deployed.get(sid)
                if row is not None:
                    items.append((page_id, sid, str(txt), "deployed_v2", self._deployed_vector(row)))
                    continue
                got = cache.execute("SELECT vec FROM sent WHERE page_id=? AND sentence_id=?",
                                    (page_id, sid)).fetchone()
                if got is not None:
                    self.metrics["cache_hit_rows"] += 1
                    items.append((page_id, sid, str(txt), "vector_cache",
                                  np.frombuffer(got[0], dtype=np.float32)))
                    continue
                items.append((page_id, sid, str(txt), "encoded_now", None))
                todo.append((len(items) - 1, sid, str(txt)))
        if todo:
            with self._lock:
                new = np.asarray(self.enc.encode([self.ch.embed_text(page_id, t) for _i, _s, t in todo],
                                                 batch_size=64, convert_to_numpy=True,
                                                 normalize_embeddings=True), dtype=np.float32)
                self.metrics["sentences_encoded"] += len(todo)
            for j, (ri, sid, txt) in enumerate(todo):
                prev = items[ri]
                items[ri] = (prev[0], prev[1], prev[2], prev[3], new[j])
            with self._seq_lock:
                self._seq += len(todo)
                base = self._seq - len(todo)
            cache.executemany("INSERT OR IGNORE INTO sent VALUES (?,?,?,?,?)",
                              [(page_id, sid, txt, new[j].tobytes(), base + j)
                               for j, (_ri, sid, txt) in enumerate(todo)])
            cache.commit()
            if int(cache.execute("SELECT COUNT(*) FROM sent").fetchone()[0]) > VECTOR_CACHE_MAX_ROWS:
                cache.execute("DELETE FROM sent WHERE seq IN (SELECT seq FROM sent ORDER BY seq LIMIT ?)",
                              (VECTOR_CACHE_EVICT_ROWS,))
                cache.commit()
                with self._lock:
                    self.metrics["vector_evictions"] += 1
                self._page_vecs.clear()
        rows = [(p, s, t, src) for p, s, t, src, _v in items]
        M = np.ascontiguousarray(np.stack([v for _p, _s, _t, _src, v in items])) if items \
            else np.zeros((0, 384), dtype=np.float32)
        return rows, M, len(todo)

    # ------------------------------------------------------------------ the frozen route
    def title_candidates(self, claim: str, q: np.ndarray) -> tuple[list, dict]:
        t0 = time.time()
        pages = self.th.select_pages(self._title_con(), claim, self.frozen["pages_per_claim"])
        select_ms = (time.time() - t0) * 1000
        with self._lock:
            self.metrics["pages_selected"] += len(pages)
        q = np.ascontiguousarray(q, dtype=np.float32)
        rows: list[tuple] = []
        blocks: list[np.ndarray] = []
        encoded = 0
        skipped = 0
        origin = {p["page_id"]: (p["member"], p["offset"], p["title_norm"]) for p in pages}
        for p in pages:
            try:
                r, M, ne = self._page_vectors(p["page_id"], self._page_sentences(p))
            except Exception:
                skipped += 1
                continue
            rows += r
            if M.shape[0]:
                blocks.append(M)
            encoded += ne
        if not rows:
            return [], {"select_ms": round(select_ms, 1), "score_ms": 0.0, "sentences": 0,
                        "pages": len(pages), "skipped_pages": skipped, "encoded": encoded}
        M = np.vstack(blocks)
        t1 = time.time()
        sc = M @ q
        order = np.argsort(-sc)[:self.frozen["candidates_per_route"]]
        out = []
        for rank, j in enumerate(order, start=1):
            pid, sid, txt, src = rows[int(j)]
            memb, off, tnorm = origin.get(pid, (None, None, None))
            out.append({"page_id": pid, "sentence_id": int(sid), "text": txt, "score": float(sc[j]),
                        "source": src, "route": "title", "rank": rank,
                        "member": memb, "offset": off, "title_norm": tnorm})
        with self._lock:
            self.metrics["sentences_searched"] += len(rows)
        return out, {"select_ms": round(select_ms, 1), "score_ms": round((time.time() - t1) * 1000, 1),
                     "sentences": len(rows), "pages": len(pages), "skipped_pages": skipped,
                     "encoded": encoded}

    @staticmethod
    def merge(a: list, b: list) -> list:
        """Max-score dedup over (page_id, sentence_id) - the frozen merge() semantics."""
        best: dict[tuple, dict] = {}
        for c in list(a) + list(b):
            key = (c["page_id"], c["sentence_id"])
            prev = best.get(key)
            if prev is None or c["score"] > prev["score"]:
                best[key] = c
        return sorted(best.values(), key=lambda c: -c["score"])

    def related(self, claim: str, q: np.ndarray, deployed_candidates: list) -> dict:
        """Top-5 discovery candidates. Called only after the verdict is final."""
        t = time.time()
        with self._lock:
            self.metrics["calls"] += 1
        title, st = self.title_candidates(claim, q)
        merged = self.merge(deployed_candidates, title)[:K]
        for i, c in enumerate(merged, start=1):
            c["rank"] = i
            c["page_title"] = self.ch.clean_wiki_text(str(c["page_id"]).replace("_", " "))
            c["used_for_verdict"] = False
            c["provenance"] = (f"{c['route']} route, sentence from "
                               f"{'the deployed FEVER corpus' if c['source'] == 'deployed_v2' else 'wiki-pages.zip'},"
                               f" vector from {c['source']}")
        return {"status": "ok", "candidates": merged, "route_stats": st,
                "merged_pool": len(deployed_candidates) + len(title),
                "total_ms": round((time.time() - t) * 1000, 1)}

    def snapshot(self) -> dict:
        with self._lock:
            rows = int(self._cache_con().execute("SELECT COUNT(*) FROM sent").fetchone()[0])
            return {"frozen_route": dict(self.frozen),
                    "page_memo": {"pages": len(self._memo), "limit_pages": PAGE_MEMO_MAX_PAGES,
                                  "policy": "least recently used, whole-page granularity"},
                    "vector_cache": {"rows": rows, "limit_rows": VECTOR_CACHE_MAX_ROWS,
                                     "evict_rows_on_overflow": VECTOR_CACHE_EVICT_ROWS,
                                     "policy": "FIFO by insertion sequence, oldest rows deleted",
                                     "path": str(_rel(CACHE_DB))},
                    "assets": {"title_index": str(_rel(TITLE_DB)), "wiki_zip": str(_rel(WIKI_ZIP))},
                    "metrics": dict(self.metrics)}


def _rel(p: Path) -> Path:
    try:
        return p.relative_to(ROOT)
    except ValueError:
        return p
