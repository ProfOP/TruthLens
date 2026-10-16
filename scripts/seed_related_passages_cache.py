r"""One-time setup for the optional related-passages path.

Copies the pilot's already-built sentence vectors (9,293 rows, measured in the frozen test pass)
into the bounded serving cache instead of re-encoding them. Adds the serving cache's `seq` column
(FIFO eviction order). Does not touch the pilot artifact: the pilot file is opened read-only.

Usage: python scripts/seed_related_passages_cache.py [--force]
"""
from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "verification-logs" / "fever-title-pilot-20261015" / "page-vector-cache.sqlite3"
DST = ROOT / "storage" / "related-passages" / "sentence-vectors.sqlite3"
SCHEMA = ("CREATE TABLE IF NOT EXISTS sent(page_id TEXT, sentence_id INT, text TEXT,"
          " vec BLOB, seq INTEGER, PRIMARY KEY(page_id, sentence_id)) WITHOUT ROWID")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="replace an existing serving cache")
    a = ap.parse_args()
    if not SRC.exists():
        print(f"ABORT: pilot vector cache missing at {SRC}")
        return 1
    if DST.exists() and not a.force:
        print(f"nothing to do: {DST} already exists (use --force to rebuild it)")
        return 0
    DST.parent.mkdir(parents=True, exist_ok=True)
    if DST.exists():
        DST.unlink()
    src = sqlite3.connect(f"file:{SRC.as_posix()}?mode=ro", uri=True)
    dst = sqlite3.connect(str(DST))
    dst.execute(SCHEMA)
    dst.execute("CREATE INDEX IF NOT EXISTS sent_seq ON sent(seq)")
    dst.execute("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
    meta = {k: str(v) for k, v in src.execute("SELECT k, v FROM meta").fetchall()}
    if "dim" not in meta:
        print(f"ABORT: pilot cache meta is incomplete: {sorted(meta)}")
        return 1
    dst.executemany("INSERT OR REPLACE INTO meta VALUES (?,?)", list(meta.items()))
    n = 0
    batch = []
    for pid, sid, txt, vec in src.execute("SELECT page_id, sentence_id, text, vec FROM sent"
                                          " ORDER BY page_id, sentence_id"):
        n += 1
        batch.append((pid, sid, txt, vec, n))
        if len(batch) >= 2000:
            dst.executemany("INSERT OR IGNORE INTO sent VALUES (?,?,?,?,?)", batch)
            batch = []
    if batch:
        dst.executemany("INSERT OR IGNORE INTO sent VALUES (?,?,?,?,?)", batch)
    dst.commit()
    wrote = dst.execute("SELECT COUNT(*) FROM sent").fetchone()[0]
    pages = dst.execute("SELECT COUNT(DISTINCT page_id) FROM sent").fetchone()[0]
    print(f"seeded {wrote} sentence vectors over {pages} pages from {SRC.name} -> {DST}")
    print("meta:", meta)
    return 0


if __name__ == "__main__":
    sys.exit(main())
