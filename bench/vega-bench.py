"""Run the Jev pre-filter benchmark requests through a local Vega checkpoint
(nandakishorm/vega-08b-public-intents: frozen Qwen3.5 backbone + "physics engine" decision head).

usage (from repo root, with the laya venv, HF_HOME pointing at the backbone cache):
  python bench/vega-bench.py <requests.jsonl> <results.jsonl> <vega-model-dir> [device] [views] [adapters]
Writes rows in the same shape as jev-bench.mjs so jev-analyze.mjs scores them unchanged.
Question types: our `noul` becomes Vega's `boolean` (probability the statement is true);
`fit` is the expected score (0-3), the same scale as Jev's `fit`.
"""
import json, os, sys, time
os.environ.setdefault("USE_TF", "0")

req_path, out_path, model_dir = sys.argv[1:4]
device = sys.argv[4] if len(sys.argv) > 4 and sys.argv[4] != "-" else None
views = int(sys.argv[5]) if len(sys.argv) > 5 else 1
adapters = sys.argv[6] if len(sys.argv) > 6 else "auto"
sys.path.insert(0, model_dir)  # vega_api / vega_common live in the model dir
from vega_api import Vega  # noqa: E402
import torch  # noqa: E402


def to_vega(questions):
    out = {}
    for k, q in questions.items():
        q = dict(q)
        if q["type"] == "noul":
            q["type"] = "boolean"
        out[k] = q
    return out


t0 = time.time()
v = Vega(model_dir, device=device, adapters=adapters)
tag = f"vega-08b{'-v' + str(views) if views > 1 else ''}{'-' + adapters if adapters != 'auto' else ''}"
print(f"loaded {tag} on {v.device} in {time.time() - t0:.1f}s, adapters: {[a['name'] for a in v.adapters]}", flush=True)

reqs = [json.loads(l) for l in open(req_path, encoding="utf-8") if l.strip()]
v.decide(reqs[0]["state"], to_vega(reqs[0]["questions"]), views=views)  # warm-up (cuda init, kernels)
with open(out_path, "w", encoding="utf-8") as out:
    for n, r in enumerate(reqs, 1):
        t = time.perf_counter()
        try:
            res = v.decide(r["state"], to_vega(r["questions"]), views=views)
            if v.device.type == "cuda":
                torch.cuda.synchronize()
            ms = round((time.perf_counter() - t) * 1000)
            a = res["answers"]
            kind, fit = a["kind"], a["fit"]
            row = {"id": r["id"], "ok": True, "ms": ms, "model": tag,
                   "usage": {"input_tokens": res["n_tokens"], "output_tokens": 0},
                   "keep": round(a["keep"]["boolean"], 4),
                   "kind": kind["choice"], "kindConfidence": round(kind["confidence"], 4),
                   "kindProbs": {k: round(p, 4) for k, p in kind["probs"].items()},
                   "fit": round(fit["expected_score"], 4), "fitConfidence": round(fit["confidence"], 4),
                   "fitProbs": {k: round(p, 4) for k, p in fit["probs"].items()},
                   "vega": {q: {"abstain": a[q]["abstain"], "unbound": a[q]["unbound"], "adapter": a[q].get("adapter"),
                                "gate": round(a[q].get("gate", 0.0), 3), "steps": a[q]["settle_steps"]} for q in a}}
        except Exception as e:  # keep going; the analyzer skips ok:false rows
            row = {"id": r["id"], "ok": False, "error": repr(e)[:300]}
            print("ERR", r["id"], row["error"], flush=True)
        out.write(json.dumps(row, ensure_ascii=False) + "\n")
        if n % 250 == 0:
            print(f"{n}/{len(reqs)} {time.time() - t0:.0f}s", flush=True)
print(f"done in {time.time() - t0:.1f}s", flush=True)
