"""Junk gate from the embeddings we already store (article_embeddings, text-embedding-3-small).

Labels = Step 1 verdicts: rejected_events.status='rejected' (junk) vs covered_events (kept).
Time split by decision time: train < VAL_FROM <= val < TEST_FROM <= test. Hyper-params and
gate thresholds are chosen on val only; test is touched once. Also compares with Jev on the
bench items that fall into the test window.

usage (repo root, laya venv has sklearn):
  python bench/emb-clf.py [db] [out-dir] [jev-results.jsonl] [bench-input.json]
"""
import json, os, sqlite3, sys
from collections import Counter, defaultdict
from urllib.parse import urlparse

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score

DB = sys.argv[1] if len(sys.argv) > 1 else "logs/news.db"
OUT = sys.argv[2] if len(sys.argv) > 2 else "bench/results/2026-09-23-emb"
JEV = sys.argv[3] if len(sys.argv) > 3 else "bench/results/2026-09-18-jev/results.jsonl"
BENCH = sys.argv[4] if len(sys.argv) > 4 else "bench/results/2026-09-18-jev/input.json"
CATS = ["tech", "gaming"]            # science has no embeddings (semanticDedup off)
VAL_FROM, TEST_FROM = "2026-08-20", "2026-08-24"
TARGET_LOSS = [0.01, 0.02, 0.04]      # share of kept (covered) items we accept losing
os.makedirs(OUT, exist_ok=True)

con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
host = lambda u: (urlparse(u).hostname or "").removeprefix("www.")

SQL = """
WITH lab AS (
  SELECT c.category, a.id AS aid, a.link, 1 AS y, c.covered_at AS t
  FROM covered_events c JOIN articles a ON a.link = c.url
  UNION ALL
  SELECT r.category, a.id, a.link, 0, r.extracted_at
  FROM rejected_events r JOIN articles a ON a.link = r.url
  WHERE r.status = 'rejected'
    AND r.url NOT IN (SELECT url FROM covered_events WHERE category = r.category)
)
SELECT lab.category, lab.aid, lab.link, lab.y, max(lab.t), e.vector
FROM lab JOIN article_embeddings e ON e.article_id = lab.aid
WHERE lab.category = ?
GROUP BY lab.aid
"""

jev = {}
if os.path.exists(JEV) and os.path.exists(BENCH):
    items = {i["id"]: i for i in json.load(open(BENCH, encoding="utf-8"))["items"]}
    for line in open(JEV, encoding="utf-8"):
        r = json.loads(line)
        if r.get("ok") and r["id"] in items:
            jev[items[r["id"]]["articleId"]] = r["keep"]

report = []
def log(s=""):
    print(s, flush=True); report.append(s)

def pct(x): return f"{100 * x:.1f}%"

def gate_rows(p_val, y_val, p_test, y_test):
    """threshold from val covered-score quantile → rates on test."""
    rows = []
    cov_val = np.sort(p_val[y_val == 1])
    for L in TARGET_LOSS:
        t = float(np.quantile(cov_val, L))
        drop = p_test < t
        junk = drop[y_test == 0].mean()
        lost = drop[y_test == 1].mean()
        share = drop.mean()
        rows.append((L, t, junk, lost, share))
    return rows

log("# Embedding junk-gate — logistic regression on text-embedding-3-small")
log(f"train < {VAL_FROM} ≤ val < {TEST_FROM} ≤ test (by Step 1 decision time)")

summary = {}
for cat in CATS:
    rows = con.execute(SQL, (cat,)).fetchall()
    X = np.stack([np.frombuffer(r[5], dtype="<f4") for r in rows])
    y = np.array([r[3] for r in rows])
    t = np.array([r[4][:10] for r in rows])
    hosts = [host(r[2]) for r in rows]
    aids = np.array([r[1] for r in rows])
    tr, va, te = t < VAL_FROM, (t >= VAL_FROM) & (t < TEST_FROM), t >= TEST_FROM

    # host one-hot for hosts seen ≥ 20× in train (source is a strong editorial prior)
    hc = Counter(h for h, m in zip(hosts, tr) if m)
    vocab = {h: i for i, h in enumerate(h for h, n in hc.items() if n >= 20)}
    H = np.zeros((len(rows), len(vocab) + 1), dtype="f4")
    for i, h in enumerate(hosts):
        H[i, vocab.get(h, len(vocab))] = 1.0
    XH = np.hstack([X, H])

    log(f"\n## {cat}")
    log(f"labeled with embedding: {len(y)} (junk {int((y == 0).sum())}, kept {int(y.sum())}); "
        f"train {tr.sum()}, val {va.sum()}, test {te.sum()} (test junk share {pct(1 - y[te].mean())})")

    best = {}
    for name, F in [("emb", X), ("emb+host", XH)]:
        scores = {}
        for C in [0.3, 1, 3, 10, 30]:
            m = LogisticRegression(C=C, class_weight="balanced", max_iter=3000).fit(F[tr], y[tr])
            scores[C] = (roc_auc_score(y[va], m.predict_proba(F[va])[:, 1]), m)
        C, (auc_val, m) = max(scores.items(), key=lambda kv: kv[1][0])
        p_val, p_te = m.predict_proba(F[va])[:, 1], m.predict_proba(F[te])[:, 1]
        auc_te = roc_auc_score(y[te], p_te)
        best[name] = (auc_te, m, F, p_val, p_te)
        log(f"\n### {name}: C={C}, AUC val {auc_val:.3f}, **AUC test {auc_te:.3f}**")
        log("| target loss (val) | threshold | junk dropped (test) | kept lost (test) | share of Step 1 input removed |")
        log("|---|---|---|---|---|")
        for L, thr, junk, lost, share in gate_rows(p_val, y[va], p_te, y[te]):
            log(f"| {pct(L)} | {thr:.3f} | {pct(junk)} | {pct(lost)} | {pct(share)} |")

    # head-to-head with Jev on bench items inside the test window
    name = max(best, key=lambda k: best[k][0])
    _, m, F, _, p_te = best[name]
    te_idx = np.where(te)[0]
    sel = [k for k, i in enumerate(te_idx) if aids[i] in jev]
    if len(sel) >= 30:
        yy = y[te_idx[sel]]
        pc = p_te[sel]
        pj = np.array([jev[aids[te_idx[k]]] for k in sel])
        log(f"\n### vs Jev on the same {len(sel)} bench items in the test window "
            f"({int((yy == 0).sum())} junk / {int(yy.sum())} kept; bench is class-balanced)")
        log(f"AUC classifier ({name}) **{roc_auc_score(yy, pc):.3f}** vs Jev keep **{roc_auc_score(yy, pj):.3f}**, "
            f"average of both ranks {roc_auc_score(yy, (pc.argsort().argsort() + pj.argsort().argsort()) / 2):.3f}")
    summary[cat] = {k: round(v[0], 4) for k, v in best.items()}

    # the most confident mistakes on test, for eyeballing
    link = {r[1]: r[2] for r in rows}
    title = dict(con.execute(f"SELECT id, title FROM articles WHERE id IN ({','.join(str(int(aids[i])) for i in te_idx)})").fetchall())
    order = np.argsort(p_te)
    log(f"\n#### {cat}: kept by Step 1, lowest classifier score (would be lost first)")
    for k in [k for k in order if y[te_idx[k]] == 1][:10]:
        i = te_idx[k]; log(f"- {p_te[k]:.3f} | {title.get(int(aids[i]), '')[:100]} <{host(link[int(aids[i])])}>")
    log(f"\n#### {cat}: junk by Step 1, highest classifier score")
    for k in [k for k in order[::-1] if y[te_idx[k]] == 0][:6]:
        i = te_idx[k]; log(f"- {p_te[k]:.3f} | {title.get(int(aids[i]), '')[:100]} <{host(link[int(aids[i])])}>")

open(os.path.join(OUT, "report.md"), "w", encoding="utf-8").write("\n".join(report) + "\n")
json.dump(summary, open(os.path.join(OUT, "summary.json"), "w"), indent=1)
