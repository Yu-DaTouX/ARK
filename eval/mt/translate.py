# -*- coding: utf-8 -*-
"""用本地 llama-server（OpenAI 兼容接口）把英文段落并发翻成中文。

  python translate.py <模型名 hymt|tg> <输入.jsonl> <输出.jsonl> [端口=8090] [并发=8]
输入每行 {id, text, ...}；输出每行 {id, zh, sec}。已翻过的 id 跳过（可中断续跑）。
提示词：Hy-MT2 用官方“术语参考 + 翻译”格式；TranslateGemma 用官方的翻译提示词。两者都要求数字、单位、剂量照原文保留。
"""
import concurrent.futures as cf, json, os, sys, time, urllib.request

GLOSSARY = [  # 求生 / 急救 / 无线电里容易译乱的词，固定译法
    ("tourniquet", "止血带"), ("splint", "夹板"), ("hypothermia", "失温"), ("heat stroke", "中暑（热射病）"),
    ("CPR", "心肺复苏（CPR）"), ("oral rehydration solution", "口服补液盐"), ("ORS", "口服补液盐（ORS）"),
    ("bowline", "称人结"), ("clove hitch", "双套结"), ("square knot", "平结"), ("deadfall", "压木陷阱"),
    ("snare", "套索陷阱"), ("tinder", "引火物"), ("bow drill", "弓钻"), ("fallout", "放射性沉降物"),
    ("dipole", "偶极天线"), ("SWR", "驻波比（SWR）"), ("coax", "同轴电缆"), ("ground plane", "地网"),
    ("jumper cables", "搭电线"), ("lug nuts", "车轮螺母"), ("multimeter", "万用表"), ("charge controller", "充电控制器"),
    ("snakebite", "蛇咬伤"), ("antivenom", "抗蛇毒血清"), ("pressure immobilization", "加压固定法"),
]
NOTE = ("数字、剂量、单位、型号、频率和公式一律照原文写，不要换算单位，也不要改动任何数字。"
        "第一行是“书名 › 章节”，照样保留这个格式（书名和章节名也翻译，型号和编号保留原样）。")


def prompt(model, text):
    if model == "hymt":
        ref = "参考下面的翻译：\n" + "\n".join(f"{a} 翻译成 {b}" for a, b in GLOSSARY)
        return f"{ref}\n\n【待翻译文本】\n{text}\n\n【翻译任务】\n1、{NOTE}\n2、将【待翻译文本】翻译为中文，只需要输出翻译后的结果，不要额外解释。"
    return ("You are a professional English (en) to Chinese (zh-Hans) translator. Your goal is to accurately convey the meaning "
            "and nuances of the original English text while adhering to Chinese grammar, vocabulary, and cultural sensitivities. "
            "Keep every number, dose, unit, model number and frequency exactly as written; never convert units. "
            "Produce only the Chinese translation, without any additional explanations or commentary. "
            f"Please translate the following English text into Chinese:\n\n\n{text}")


PARAMS = {"hymt": dict(temperature=0.7, top_p=0.6, top_k=20, repeat_penalty=1.05),
          "tg": dict(temperature=0.0)}


def call(port, model, text):
    body = dict(messages=[dict(role="user", content=prompt(model, text))], max_tokens=2048, **PARAMS[model])
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    t = time.time()
    r = json.load(urllib.request.urlopen(req, timeout=600))
    return r["choices"][0]["message"]["content"].strip(), time.time() - t, r.get("usage", {}).get("completion_tokens", 0)


def main():
    model, src, dst = sys.argv[1:4]
    port = int(sys.argv[4]) if len(sys.argv) > 4 else 8090
    par = int(sys.argv[5]) if len(sys.argv) > 5 else 8
    done = {json.loads(l)["id"] for l in open(dst, encoding="utf-8")} if os.path.exists(dst) else set()
    todo = [r for r in map(json.loads, open(src, encoding="utf-8")) if r["id"] not in done]
    t0, toks = time.time(), 0
    with open(dst, "a", encoding="utf-8", newline="\n") as f, cf.ThreadPoolExecutor(par) as ex:
        futs = {ex.submit(call, port, model, r["text"]): r for r in todo}
        for i, fu in enumerate(cf.as_completed(futs), 1):
            r = futs[fu]
            try:
                zh, sec, n = fu.result()
            except Exception as e:
                print("失败", r["id"], e); continue
            toks += n
            f.write(json.dumps(dict(id=r["id"], zh=zh, sec=round(sec, 1)), ensure_ascii=False) + "\n"); f.flush()
            if i % 20 == 0 or i == len(todo):
                el = time.time() - t0
                print(f"{i}/{len(todo)}  {el:.0f} 秒  合计 {toks / el:.0f} token/秒", flush=True)


if __name__ == "__main__":
    main()
