"""Frozen embedding junk gate: fit once on pre-TRAIN_TO data, save, then score newer records.

1. Fit on TRAIN_DB decisions < TRAIN_TO (same recipe as emb-clf-lowloss.py: C per category,
   thresholds from 5 contiguous time-fold OOF predictions), save to MODEL (.npz).
2. Sanity: re-score the old test window TRAIN_TO..CHECK_TO from TRAIN_DB (must match the
   2026-09-23 lowloss.md numbers closely).
3. Score EVAL_DB decisions >= EVAL_FROM without refitting: overall + per-week, and list
   every kept item the chosen operating points would have dropped.

usage: python bench/emb-clf-frozen.py <train-db> <eval-db> [out-dir]
"""
import os, sqlite3, sys
from urllib.parse import urlparse
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score

TRAIN_DB, EVAL_DB = sys.argv[1], sys.argv[2]
OUT = sys.argv[3] if len(sys.argv) > 3 else "bench/results/2026-09-23-emb"
MODEL = os.path.join(OUT, "model-frozen-train-lt-2026-08-24.npz")
TRAIN_TO, CHECK_TO, EVAL_FROM = "2026-08-24", "2026-09-03", "2026-09-03"
C = {"tech": 1, "gaming": 10}
LOSSES = [0.0, 0.001, 0.0025, 0.005, 0.01, 0.02]
CHOSEN = {"tech": 0.005, "gaming": 0.0025}   # operating points proposed to the user
SQL = """
WITH lab AS (
  SELECT c.category, a.id AS aid, 1 AS y, c.covered_at AS t FROM covered_events c JOIN articles a ON a.link = c.url
  UNION ALL
  SELECT r.category, a.id, 0, r.extracted_at FROM rejected_events r JOIN articles a ON a.link = r.url
  WHERE r.status = 'rejected' AND r.url NOT IN (SELECT url FROM covered_events WHERE category = r.category)
)
SELECT lab.y, max(lab.t), e.vector, a.title, a.link, lab.aid
FROM lab JOIN article_embeddings e ON e.article_id = lab.aid JOIN articles a ON a.id = lab.aid
WHERE lab.category = ? GROUP BY lab.aid ORDER BY max(lab.t)
"""
out = []
def log(s=""):
    print(s, flush=True); out.append(s)
pct = lambda x: f"{100 * x:.2f}%"
host = lambda u: (urlparse(u).hostname or "").removeprefix("www.")
fit = lambda X, y, c: LogisticRegression(C=c, class_weight="balanced", max_iter=3000).fit(X, y)

def load(db, cat):
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    rows = con.execute(SQL, (cat,)).fetchall()
    cov = {r[0]: r[1:] for r in con.execute(
        "SELECT url, event_type, newsworthiness, digest_fit FROM covered_events WHERE category = ?", (cat,))}
    con.close()
    return (np.array([r[0] for r in rows]), np.array([r[1][:10] for r in rows]),
            np.stack([np.frombuffer(r[2], dtype="<f4") for r in rows]), [r[3] for r in rows], [r[4] for r in rows], cov)

def table(p, y, thr_by_loss):
    nk = int(y.sum())
    log("| target loss | threshold | kept lost | junk dropped | Step 1 input removed |")
    log("|---|---|---|---|---|")
    for L, thr in thr_by_loss.items():
        d = p < thr; lost = int(d[y == 1].sum())
        log(f"| {pct(L)} | {thr:.4f} | {lost}/{nk} ({pct(lost / nk)}) | {pct(d[y == 0].mean())} | {pct(d.mean())} |")

saved = {}
for cat in ["tech", "gaming"]:
    y, t, X, _, _, _ = load(TRAIN_DB, cat)
    pre = t < TRAIN_TO; Xp, yp = X[pre], y[pre]
    oof = np.zeros(len(yp))
    for f in np.array_split(np.arange(len(yp)), 5):
        m = np.ones(len(yp), bool); m[f] = False
        oof[f] = fit(Xp[m], yp[m], C[cat]).predict_proba(Xp[f])[:, 1]
    model = fit(Xp, yp, C[cat])
    kept = np.sort(oof[yp == 1])
    thr = {L: (kept[0] * 0.999 if L == 0 else float(np.quantile(kept, L))) for L in LOSSES}
    saved[f"{cat}_coef"], saved[f"{cat}_intercept"] = model.coef_[0], model.intercept_
    saved[f"{cat}_losses"], saved[f"{cat}_thresholds"] = np.array(LOSSES), np.array([thr[L] for L in LOSSES])
    log(f"\n# {cat}")
    log(f"trained on {TRAIN_DB} decisions {min(t[pre])}..{max(t[pre])}: {int((yp == 0).sum())} junk / {int(yp.sum())} kept")

    chk = (t >= TRAIN_TO) & (t < CHECK_TO)
    pc = model.predict_proba(X[chk])[:, 1]
    log(f"\n## sanity: old test window {TRAIN_TO}..{CHECK_TO} (AUC {roc_auc_score(y[chk], pc):.3f})")
    table(pc, y[chk], thr)

    ye, te, Xe, titles, links, cov = load(EVAL_DB, cat)
    ev = te >= EVAL_FROM
    p = model.predict_proba(Xe[ev])[:, 1]; yv = ye[ev]; tv = te[ev]
    idx = np.where(ev)[0]
    log(f"\n## NEW records {min(tv)}..{max(tv)} from {EVAL_DB}: {int((yv == 0).sum())} junk / {int(yv.sum())} kept, "
        f"**AUC {roc_auc_score(yv, p):.3f}** (no refit)")
    table(p, yv, thr)
    L = CHOSEN[cat]; th = thr[L]
    log(f"\n### per week at the chosen point (target {pct(L)}, threshold {th:.4f})")
    log("| week from | junk / kept | AUC | kept lost | junk dropped | input removed |")
    log("|---|---|---|---|---|---|")
    for w0, w1 in [("2026-09-03", "2026-09-10"), ("2026-09-10", "2026-09-17"), ("2026-09-17", "2026-09-24")]:
        s = (tv >= w0) & (tv < w1)
        if s.sum() == 0 or len(set(yv[s])) < 2: continue
        d = p[s] < th; yy = yv[s]
        log(f"| {w0} | {int((yy == 0).sum())} / {int(yy.sum())} | {roc_auc_score(yy, p[s]):.3f} | "
            f"{int(d[yy == 1].sum())} ({pct(d[yy == 1].mean())}) | {pct(d[yy == 0].mean())} | {pct(d.mean())} |")
    log(f"\n### kept items that would be dropped at the chosen point")
    for k in np.argsort(p):
        if p[k] >= th: break
        if yv[k] != 1: continue
        i = idx[k]; c = cov.get(links[i], ("?", "?", "?"))
        log(f"- {p[k]:.4f} | {tv[k]} | step1 {c[0]} nw={c[1]} fit={c[2]} | {titles[i][:100]} <{host(links[i])}>")

np.savez(MODEL, **saved)
open(os.path.join(OUT, "frozen-eval.md"), "w", encoding="utf-8").write("\n".join(out) + "\n")
print("saved", MODEL)
