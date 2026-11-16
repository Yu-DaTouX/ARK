# -*- coding: utf-8 -*-
"""LoRA 微调 Gemma 4（E2B / E4B），只训练回答部分；训完合并成完整权重，交给 litert-torch 导出成手机用的 .litertlm。

  python train_lora.py train  <base HF 目录> <train.jsonl> <输出目录>
  python train_lora.py merge  <base HF 目录> <输出目录>        # 生成 <输出目录>/merged（完整 HF 权重）

train.jsonl 每行 {"system": ..., "user": ..., "answer": ...}，system/user 与 App 发给模型的完全一致（Prompts.kt / run_eval.py）。
格式与手机上的 LiteRT-LM 一致：<|turn>system … <|turn>user … <|turn>model\n回答<turn|>，不开思考。
"""
import json, math, os, random, sys, time

import torch
from peft import LoraConfig, PeftModel, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer, get_cosine_schedule_with_warmup

TARGET = r"model\.language_model\.layers\.\d+\.(self_attn\.(q|k|v|o)_proj|mlp\.(gate|up|down)_proj)"
MAX_LEN = 3072


def encode(tok, ex):
    msgs = [{"role": "system", "content": ex["system"]}, {"role": "user", "content": ex["user"]}]
    prompt = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    p = tok(prompt, add_special_tokens=False)["input_ids"]
    a = tok(ex["answer"].strip() + "<turn|>", add_special_tokens=False)["input_ids"]
    ids = (p + a)[:MAX_LEN]
    labels = ([-100] * len(p) + a)[:MAX_LEN]
    return ids, labels


def slim_gpu(model):
    """显存只有 16 GB：用不到的视觉/音频塔（约 0.5B）和每层嵌入表（PLE，2.35B 参数、只做查表）放在内存里。
    溢出到共享内存时训练会慢几十倍。"""
    m = model.model
    for n in ("vision_tower", "audio_tower", "embed_vision", "embed_audio"):
        if getattr(m, n, None) is not None:
            getattr(m, n).to("cpu")
    ple = m.language_model.embed_tokens_per_layer
    ple.to("cpu")
    ple.register_forward_pre_hook(lambda mod, args: tuple(a.to("cpu") for a in args))
    ple.register_forward_hook(lambda mod, args, o: o.to("cuda"))
    torch.cuda.empty_cache()


def answer_loss(model, ids, labels):
    """只算回答部分的 logits（词表 26 万，全序列算 logits 要几 GB 显存）。回答在序列末尾。"""
    n = sum(1 for l in labels if l != -100)
    x = torch.tensor([ids], device="cuda")
    logits = model(input_ids=x, logits_to_keep=n + 1).logits[0, :-1].float()
    return torch.nn.functional.cross_entropy(logits, x[0, -n:])


def train(base, data, out, epochs=2, lr=1e-4, accum=8, r=16):
    tok = AutoTokenizer.from_pretrained(base)
    model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.bfloat16, device_map="cuda", attn_implementation="sdpa")
    slim_gpu(model)
    model.config.use_cache = False
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(r=r, lora_alpha=2 * r, lora_dropout=0.05, target_modules=TARGET, task_type="CAUSAL_LM"))
    model.print_trainable_parameters()

    rows = [json.loads(l) for l in open(data, encoding="utf-8") if l.strip()]
    exs = [encode(tok, ex) for ex in rows]
    print(f"{len(exs)} 条，平均 {sum(len(i) for i, _ in exs) / len(exs):.0f} token，最长 {max(len(i) for i, _ in exs)}，"
          f"显存 {torch.cuda.memory_allocated() / 2**30:.1f}G", flush=True)
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr, weight_decay=0.0)
    steps = math.ceil(len(exs) * epochs / accum)
    sched = get_cosine_schedule_with_warmup(opt, max(1, steps // 20), steps)
    model.train()
    rnd = random.Random(0)
    t0, step, run = time.time(), 0, []
    for ep in range(epochs):
        order = list(range(len(exs)))
        rnd.shuffle(order)
        for i, k in enumerate(order):
            ids, labels = exs[k]
            loss = answer_loss(model, ids, labels)
            (loss / accum).backward()
            run.append(loss.item())
            if (i + 1) % accum == 0 or i == len(order) - 1:
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                opt.step(); sched.step(); opt.zero_grad(set_to_none=True)
                step += 1
                if step % 5 == 0 or step == 1:
                    print(f"ep {ep} step {step}/{steps} loss {sum(run) / len(run):.4f} lr {sched.get_last_lr()[0]:.2e} "
                          f"{time.time() - t0:.0f}s mem {torch.cuda.max_memory_allocated() / 2**30:.1f}G", flush=True)
                    run = []
        model.save_pretrained(os.path.join(out, f"lora_ep{ep}"))
    model.save_pretrained(os.path.join(out, "lora"))
    tok.save_pretrained(os.path.join(out, "lora"))


def merge(base, out):
    model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.bfloat16, device_map="cpu")
    model = PeftModel.from_pretrained(model, os.path.join(out, "lora")).merge_and_unload()
    dst = os.path.join(out, "merged")
    model.save_pretrained(dst, safe_serialization=True)
    AutoTokenizer.from_pretrained(base).save_pretrained(dst)
    # 导出工具要用到的其他文件（处理器配置、聊天模板等）原样复制
    import shutil
    for f in os.listdir(base):
        if f.endswith((".json", ".jinja", ".model")) and not os.path.exists(os.path.join(dst, f)):
            shutil.copy(os.path.join(base, f), dst)
    print("合并完成：", dst)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "train":
        train(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        merge(sys.argv[2], sys.argv[3])
