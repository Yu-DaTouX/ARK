# -*- coding: utf-8 -*-
"""微调前后对比：40 道真实场景题，用同一批检索段落（results_rag_v2 里 E2B 实际拿到的 4 段，去掉维基脚注号），
只比较“拿到资料后怎么答”。贪心解码，和手机一致。

  python eval_hf.py <base HF 目录> <输出.jsonl> [LoRA 目录]
"""
import json, os, re, sys, time

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

sys.path.insert(0, "F:/ARK/eval")
sys.path.insert(0, "F:/ARK/data")
import rag_real as RR
import run_eval as R
from build_pack import clean

SRC = "F:/ARK/eval/results_rag_v2/gemma4-e2b.jsonl"


def main(base, out, lora=None):
    tok = AutoTokenizer.from_pretrained(base)
    model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.bfloat16, device_map="cuda")
    if lora:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, lora).merge_and_unload()
    model.eval()
    recs = [json.loads(l) for l in open(SRC, encoding="utf-8")]
    recs = recs[:int(os.environ.get("ARK_N", len(recs)))]  # ARK_N=3：只跑前几题，快速抽查
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        for r in recs:
            ps = [dict(p, text=clean(p["text"])) for p in r["passages"]]
            msgs = [{"role": "system", "content": R.SYS_RAG}, {"role": "user", "content": RR.rag_user(ps, r["q"])}]
            ids = tok.apply_chat_template(msgs, add_generation_prompt=True, enable_thinking=False, return_tensors="pt",
                                          return_dict=True).to("cuda")
            t0 = time.time()
            with torch.no_grad():
                g = model.generate(**ids, max_new_tokens=900, do_sample=False)
            new = g[0, ids["input_ids"].shape[1]:]
            ans = tok.decode(new, skip_special_tokens=True).strip()
            fin = "length" if len(new) >= 900 else "stop"
            f.write(json.dumps(dict(q=r["q"], terms=r["terms"], passages=ps, answer=ans, tokens=len(new), finish=fin,
                                    bad_numbers=RR.unverified(ans, ps, r["q"])), ensure_ascii=False) + "\n")
            f.flush()
            print(f"{len(new):4d} tok {time.time() - t0:5.1f}s  {r['q']}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
