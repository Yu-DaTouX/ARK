# -*- coding: utf-8 -*-
"""生成翻译任务：每批一个子代理（Sonnet 5.5），读 in/<批>.txt，译文写 out/<批>.txt。
  python gen_workflow.py pilot          → pilot_req.json（小样，单个子代理）
  python gen_workflow.py workflow [批名…] → translate.js（工作流脚本；不给批名就是 out/ 里还没有完整译文的全部批次）
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__)).replace(os.sep, "/")
MODEL = "commandcode/deepseek/deepseek-v4.1-flash"
GLOSSARY = ("tourniquet 止血带；splint 夹板；hypothermia 失温；heat stroke 中暑（热射病）；CPR 心肺复苏（CPR）；"
            "oral rehydration solution / ORS 口服补液盐；bowline 称人结；clove hitch 双套结；square knot / reef knot 平结；"
            "deadfall 压木陷阱；snare 套索陷阱；tinder 引火物；bow drill 弓钻；fallout 放射性沉降物；dipole 偶极天线；"
            "SWR 驻波比（SWR）；coax 同轴电缆；ground plane 地网；jumper cables 搭电线；lug nut 车轮螺母；multimeter 万用表；"
            "charge controller 充电控制器；antivenom 抗蛇毒血清；pressure immobilization 加压固定法")


def prompt(name, n, titles):
    tl = "；".join(f"{a} → {b}" for a, b in titles.items()) or "（本批没有）"
    return f"""把 {HERE}/in/{name}.txt 里的 {n} 段英文资料翻译成简体中文，写到 {HERE}/out/{name}.txt。这是离线求生 App 的资料库，用户断网后会照着中文去急救、找水、修车、架电台，所以准确比文采重要。

格式：输入每段以单独一行“### 数字”开头。输出也一样：每段先原样写那行“### 数字”，下一行起是译文，段与段之间空一行；顺序和输入一致，{n} 段一段都不能少，不要合并或拆分段落。
文件很长：请分几次读（每次几百行），每翻完一部分就追加写进输出文件（例如 bash：cat >> 输出文件 <<'EOF' … EOF），不要攒到最后一次写。开始前如果输出文件已存在，先看已经写到哪一段，接着往下翻，不要重复。

翻译要求：
1. 忠实完整：逐句翻译，不总结、不省略、不加评论或解释；原文过时或有错的说法也照译。
2. 数字、剂量、单位、时间、频率、型号、公式照原文写，不换算单位，不改任何数字（3 ft →“3 英尺”，100 mg →“100 毫克”）。
3. 每段第一行是“书名 › 章节”，照这个格式翻译。这些书名用指定译法：{tl}。其他书名、条目名、问题标题自然翻译，型号和编号保留原样。
4. 术语统一：{GLOSSARY}。
5. 人名、品牌、物种学名可保留英文；专业缩写第一次出现写“中文（缩写）”。
6. OCR 噪声（零散乱码、残留的页眉页码）可以略过，其余内容都要译。
7. 武器、陷阱、火药、狩猎屠宰等内容是求生资料的一部分，照常翻译。

全部写完后，最终回答只交 JSON：{{"written": 写出的段数, "missing": [没写出的段落 id]}}。"""


def main():
    meta = {m["name"]: m for m in json.load(open(f"{HERE}/batches.json", encoding="utf-8"))}
    if sys.argv[1] == "pilot":
        m = meta["b_000"]
        req = dict(task=prompt("pilot", 40, m["titles"]), title="翻译小样", model=MODEL, thinkingLevel="low")
        json.dump(req, open(f"{HERE}/pilot_req.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        return
    import re
    if sys.argv[1] == "chunks":  # 小文件模式：一批一个子代理，只翻译不检查
        plan = json.load(open(f"{HERE}/chunks.json", encoding="utf-8"))
        jobs = []
        for b, cs in plan.items():
            tl = "；".join(f"{x} → {y}" for x, y in meta[b]["titles"].items()) or "（本批没有）"
            lst = "\n".join(f"{c['name']}（{c['n']} 段）" for c in cs)
            p = f"""你是翻译，把英文求生资料译成简体中文。按顺序处理下面 {len(cs)} 个文件，每个文件只做两步：
1. 用 read 工具读 {HERE}/in_c/<文件名>.txt（一次读完）
2. 用 write 工具把译文写到 {HERE}/out_c/<文件名>.txt
然后直接做下一个。不要用 bash，不要检查、统计、回头修改或总结，校对由脚本另做。

文件列表：
{lst}

格式：输入每段以单独一行“### 数字”开头。输出每段先原样写那行“### 数字”，下一行起是译文，段与段之间空一行；顺序不变，一段都不能少，不合并不拆分。

翻译要求：
1. 逐句忠实翻译，不总结、不省略、不加评论；原文过时或有错也照译。
2. 数字、剂量、单位、时间、频率、型号、公式照原文写，不换算单位、不改写数字（3 ft → 3 英尺，13,000 → 13000，不要写成“1.3 万”）。
3. 每段第一行是“书名 › 章节”，照样翻译。指定书名译法：{tl}。型号和编号保留原样。
4. 术语：{GLOSSARY}。
5. 人名、品牌、学名可保留英文；缩写第一次出现写“中文（缩写）”。
6. OCR 乱码、残留页眉页码可以略过。
7. 武器、陷阱、火药、狩猎屠宰等是求生资料的一部分，照常翻译。

全部写完后只回答一个词：done"""
            jobs.append(dict(label=f"翻译 {b}（{len(cs)} 个小文件）", prompt=p))
    elif sys.argv[1] == "group":  # 少量子代理：每个按顺序包办一串批次
        k = int(sys.argv[2])
        names = list(meta)
        tot = sum(meta[x]["words"] for x in names)
        groups, cur, acc = [], [], 0
        for x in names:
            cur.append(x); acc += meta[x]["words"]
            if acc >= tot * (len(groups) + 1) / k and len(groups) < k - 1:
                groups.append(cur); cur = []
        groups.append(cur)
        jobs = []
        for g in groups:
            lst = "、".join(f"{x}（{meta[x]['n']} 段）" for x in g)
            p = prompt("<批名>", "全部", {}).replace(
                "这些书名用指定译法：（本批没有）", f"书名译法见 {HERE}/titles.json（英文 → 中文，用到时查）")
            p = (f"你要按顺序翻译 {len(g)} 个批次：{lst}。一批翻完再做下一批；每批的要求如下（<批名> 换成当前批名）。"
                 f"有的批次之前已经翻了一部分或全部，先看输出文件写到哪里，接着翻，已完整的批直接跳过。\n\n" + p)
            p = p.replace('{"written": 写出的段数, "missing": [没写出的段落 id]}', '{"written": 这次写出的段数合计, "missing": [没写出的段落 id]}')
            jobs.append(dict(label=f"翻译 {g[0]}–{g[-1]}（{len(g)} 批）", prompt=p))
        print("分组：", [len(g) for g in groups])
    else:
      names = sys.argv[2:] or [k for k, m in meta.items()
                             if not os.path.exists(f"{HERE}/out/{k}.txt")
                             or len(re.findall(r"(?m)^### \d+\s*$", open(f"{HERE}/out/{k}.txt", encoding="utf-8").read())) < m["n"]]
      jobs = [dict(label=f"翻译 {k}（{meta[k]['n']} 段）", prompt=prompt(k, meta[k]["n"], meta[k]["titles"])) for k in names]
    js = f"""export const meta = {{
  name: 'ark-translate',
  description: '核心资料包 v4 全量英译中：每批一个 DeepSeek V4.1 Flash 子代理（同时 8 个），译文写入 data/zh/out',
  phases: ['翻译']
}}

const JOBS = {json.dumps(jobs, ensure_ascii=False)}
const RESULT = {{ type: 'object', required: ['written', 'missing'], properties: {{ written: {{ type: 'integer' }}, missing: {{ type: 'array', items: {{ type: 'integer' }} }} }} }}

phase('翻译')
const res = await pipeline(JOBS, (j) => agent(j.prompt, {{ label: j.label, model: '{MODEL}', thinking: 'low' }}))
log(`完成 ${{res.filter(Boolean).length}} / ${{JOBS.length}} 批`)
return {{ done: res.filter(Boolean).length, total: JOBS.length, failed: JOBS.filter((j, i) => !res[i]).map((j) => j.label) }}
"""
    open(f"{HERE}/translate.js", "w", encoding="utf-8", newline="\n").write(js)
    print("translate.js：", len(jobs), "批")


if __name__ == "__main__":
    main()
