"""Run the Jev pre-filter benchmark requests through a local Laya checkpoint.

usage (from repo root, with the laya venv):
  python bench/laya-bench.py <requests.jsonl> <results.jsonl> <laya-model-dir> [subfolder] [device]
Writes rows in the same shape as jev-bench.mjs so jev-analyze.mjs scores them unchanged.
Uses the checkpoint's own rl_agent_api.RLAgent (Jev-compatible system_one).
"""
import json, os, sys, time
os.environ.setdefault("USE_TF", "0")

req_path, out_path, model_root = sys.argv[1:4]
sub = sys.argv[4] if len(sys.argv) > 4 and sys.argv[4] != "-" else ""
device = sys.argv[5] if len(sys.argv) > 5 else None
sys.path.insert(0, model_root)  # rl_agent_api / rl_common live at the repo root
from rl_agent_api import RLAgent  # noqa: E402
import torch  # noqa: E402

t0 = time.time()
agent = RLAgent(os.path.join(model_root, sub) if sub else model_root, device=device)
print(f"loaded {sub or 'english'} on {agent.device} in {time.time() - t0:.1f}s", flush=True)

reqs = [json.loads(l) for l in open(req_path, encoding="utf-8") if l.strip()]
agent.system_one(reqs[0]["state"], reqs[0]["questions"])  # warm-up (cuda init, kernels)
with open(out_path, "w", encoding="utf-8") as out:
    for n, r in enumerate(reqs, 1):
        t = time.perf_counter()
        try:
            res = agent.system_one(r["state"], r["questions"])
            if agent.device.type == "cuda":
                torch.cuda.synchronize()
            ms = round((time.perf_counter() - t) * 1000)
            a = res["answers"]
            row = {"id": r["id"], "ok": True, "ms": ms, "model": f"laya-{sub or 'english'}", "usage": res["usage"],
                   "keep": a["keep"]["noul"],
                   "kind": a["kind"]["choice"], "kindConfidence": a["kind"]["confidence"], "kindProbs": a["kind"]["probabilities"],
                   "fit": a["fit"]["score"], "fitConfidence": a["fit"]["confidence"], "fitProbs": a["fit"]["probabilities"]}
        except Exception as e:  # keep going; the analyzer skips ok:false rows
            row = {"id": r["id"], "ok": False, "error": repr(e)[:300]}
            print("ERR", r["id"], row["error"], flush=True)
        out.write(json.dumps(row, ensure_ascii=False) + "\n")
        if n % 250 == 0:
            print(f"{n}/{len(reqs)}", flush=True)
print(f"done in {time.time() - t0:.1f}s", flush=True)
