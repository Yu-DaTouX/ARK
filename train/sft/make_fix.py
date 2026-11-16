# -*- coding: utf-8 -*-
"""第 3 版数据：把“资料覆盖的话题却以‘资料中没有’开头”的 145 条答案交给老师（Opus 5.5）重写。

  python make_fix.py        → teacher/in/fix_NN.md + teacher/in/fix_reqNN.json（每批 25 条）

第 2 版学生模型学成了“先说资料中没有，再给做法”（40 题里 14 题这样开头，多数其实可答）。
重写规则：资料里只要有能直接帮提问的人行动的内容，第一句就给做法，缺的部分放最后一句交代。
"""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
R = [json.loads(l) for l in open(os.path.join(HERE, "train_all.jsonl"), encoding="utf-8")]
todo = [r for r in R if r["covered"] and not r["answerable"] and r["answer"].lstrip().startswith("资料中没有")]
print("要重写", len(todo))

TASK = """你是离线求生助手训练数据的老师。请先读 F:/ARK/train/sft/STYLE.md（答案写法规则），再读 {path}（{n} 条：每条有编号、App 里实际发给模型的资料和问题、以及之前写的答案）。

这些题的话题在资料库里是有的，但之前的答案都以“资料中没有提到……”开头。学生模型因此学成了“不管资料有没有，先说资料中没有”，评测里大量本来能答的题被它拒答。请逐条重新判断、重写：

1. 资料里只要有能直接帮提问的人采取行动的内容（哪怕不完整、哪怕说法不完全对应问题，比如问烧伤资料讲烫伤、问腿骨折资料讲上肢夹板的通用做法），就按 STYLE 第 1–2 条正常回答：第一句直接给最重要的做法或结论，然后要点。资料缺的关键部分，在最后用一句“资料没有提到……”交代，不要放在开头。
2. 只有资料完全帮不上（四段都和问题无关，或者只有统计、历史、政策之类不能指导行动的内容）时，才保留“资料中没有提到……”开头，后面最多一两句部分相关的信息。
3. 其余规则照旧：只用资料里的信息，数字照抄原文（App 会逐个核对），句后标出处 [n]，60–200 字，用“- ”列表，不照抄过时或有害的做法，普通人做不到的不展开。

输出：只输出一个 jsonl 代码块，每条一行，共 {n} 行，顺序同输入：
{{"id": 编号, "mode": "answer" 或 "refuse", "answer": "答案全文，换行写成 \\n"}}
mode 为 refuse 表示按第 2 条保留了“资料中没有提到”开头。"""

os.makedirs(os.path.join(HERE, "teacher", "in"), exist_ok=True)
B = 25
for k in range(0, len(todo), B):
    part = todo[k:k + B]
    nb = k // B
    path = os.path.join(HERE, "teacher", "in", f"fix_{nb:02d}.md").replace("\\", "/")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for r in part:
            f.write(f"\n\n==================== 编号 {r['id']} ====================\n")
            f.write(r["user"].strip() + "\n\n---- 之前的答案 ----\n" + r["answer"].strip() + "\n")
    req = dict(task=TASK.format(path=path, n=len(part)), title=f"重写拒答{nb:02d}", model="anthropic/claude-opus-5-5",
               thinkingLevel="high", readOnly=True,
               brief=dict(goal=f"按规则重写 {len(part)} 条以“资料中没有”开头的训练答案", deliverables=[f"一个 jsonl 代码块，{len(part)} 行"]))
    json.dump(req, open(os.path.join(HERE, "teacher", "in", f"fix_req{nb:02d}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(path, len(part), len(req["task"]))
