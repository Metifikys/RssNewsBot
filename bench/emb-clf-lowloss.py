"""Low-loss operating points for the embedding junk gate.

Threshold for a target loss L (share of Step 1 "kept" items we accept dropping) is taken from
out-of-fold predictions over everything before TEST_FROM (5 contiguous time folds), the final
model is fit on all pre-test data, and the gate is measured once on the test window.
Also prints the test-set "oracle" curve (best achievable at exactly k lost items).

usage: python bench/emb-clf-lowloss.py [db] [out.md]
"""
import sqlite3, sys
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score

DB = sys.argv[1] if len(sys.argv) > 1 else "logs/news.db"
OUT = sys.argv[2] if len(sys.argv) > 2 else "bench/results/2026-09-23-emb/lowloss.md"
TEST_FROM = "2026-08-24"
C = {"tech": 1, "gaming": 10}
LOSSES = [0.0, 0.001, 0.0025, 0.005, 0.01, 0.02]
SQL = """
WITH lab AS (
  SELECT c.category, a.id AS aid, 1 AS y, c.covered_at AS t FROM covered_events c JOIN articles a ON a.link = c.url
  UNION ALL
  SELECT r.category, a.id, 0, r.extracted_at FROM rejected_events r JOIN articles a ON a.link = r.url
  WHERE r.status = 'rejected' AND r.url NOT IN (SELECT url FROM covered_events WHERE category = r.category)
)
SELECT lab.y, max(lab.t), e.vector FROM lab JOIN article_embeddings e ON e.article_id = lab.aid
WHERE lab.category = ? GROUP BY lab.aid ORDER BY max(lab.t)
"""
con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
out = []
def log(s=""):
    print(s, flush=True); out.append(s)
pct = lambda x: f"{100 * x:.2f}%"
fit = lambda X, y, c: LogisticRegression(C=c, class_weight="balanced", max_iter=3000).fit(X, y)

for cat in ["tech", "gaming"]:
    rows = con.execute(SQL, (cat,)).fetchall()
    y = np.array([r[0] for r in rows]); t = np.array([r[1][:10] for r in rows])
    X = np.stack([np.frombuffer(r[2], dtype="<f4") for r in rows])
    pre, te = t < TEST_FROM, t >= TEST_FROM
    Xp, yp = X[pre], y[pre]
    oof = np.zeros(len(yp))
    for f in np.array_split(np.arange(len(yp)), 5):          # contiguous time folds
        m = np.ones(len(yp), bool); m[f] = False
        oof[f] = fit(Xp[m], yp[m], C[cat]).predict_proba(Xp[f])[:, 1]
    p = fit(Xp, yp, C[cat]).predict_proba(X[te])[:, 1]; yt = y[te]
    nk, nj = int(yt.sum()), int((yt == 0).sum())
    log(f"\n## {cat} — test {TEST_FROM}..: {nj} junk / {nk} kept, AUC {roc_auc_score(yt, p):.3f}; "
        f"threshold source: {int(yp.sum())} kept pre-test (OOF)")
    log("| target loss | threshold | kept lost on test | junk dropped | Step 1 input removed |")
    log("|---|---|---|---|---|")
    kept_oof = np.sort(oof[yp == 1])
    for L in LOSSES:
        thr = kept_oof[0] * 0.999 if L == 0 else float(np.quantile(kept_oof, L))
        d = p < thr
        lost = int(d[yt == 1].sum())
        log(f"| {pct(L)} | {thr:.4f} | {lost}/{nk} ({pct(lost / nk)}) | {pct(d[yt == 0].mean())} | {pct(d.mean())} |")
    log("\noracle on test (threshold set just below the k-th lowest kept item):")
    ks = np.sort(p[yt == 1])
    for k in [0, 1, 2, 4, 8]:
        thr = ks[k]
        log(f"- lose {k}/{nk} ({pct(k / nk)}): junk dropped {pct((p[yt == 0] < thr).mean())}")

open(OUT, "w", encoding="utf-8").write("\n".join(out) + "\n")
