# -*- coding: utf-8 -*-
"""问答站（Stack Exchange 的 ZIM）→ 段落：每题只取“问题 + 最佳回答”（票数最高的回答），问题和回答的票数都够才收。

  python extract_se.py stats <zim>                          → 看票数分布，用来定门槛
  python extract_se.py <zim> <书名> <问题最低票> <回答最低票>  → F:/ARK/data/chunks/<zim名>.jsonl
输出格式同 extract_chunks.py：{src, doc, title, text}，每段首行“书名 › 问题标题”，正文是问题（前 120 词）+ 最佳回答。
"""
import json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_chunks as X

Q_WORDS = 120


def inner_div(h, start):
    """start 处是一个 <div …>，返回它和配对 </div> 之间的 HTML。"""
    i = h.index(">", start) + 1
    depth = 1
    for m in re.finditer(r"<(/?)div\b", h[i:]):
        depth += -1 if m.group(1) else 1
        if depth == 0:
            return h[i:i + m.start()]
    return h[i:]


def body_after(h, pos):
    j = h.find('class="s-prose js-post-body"', pos)
    if j < 0: return ""
    return X.html_text(inner_div(h, h.rindex("<div", 0, j)))[1]


def parse(h):
    q = re.search(r'<div id="question" class="question"[^>]*data-score="(-?\d+)"', h)
    if not q: return None
    ans = [(int(m.group(2)), m.start()) for m in re.finditer(r'<div id="answer-\d+" class="answer([^"]*)"[^>]*data-score="(-?\d+)"', h)]
    if not ans: return None
    a_score, a_pos = max(ans)
    return int(q.group(1)), body_after(h, q.start()), a_score, body_after(h, a_pos)


def pages(path):
    from libzim.reader import Archive
    a = Archive(path)
    for i in range(a.all_entry_count):
        e = a.get_entry_by_id(i)
        if e.is_redirect or not re.match(r"questions/\d+/", e.path): continue
        r = parse(bytes(e.get_item().content).decode("utf-8", "ignore"))
        if r: yield e.path, e.title, r


def main():
    if sys.argv[1] == "stats":
        rows = [(qs, as_, len(ab.split())) for _, _, (qs, _, as_, ab) in pages(sys.argv[2])]
        print("题数", len(rows))
        for qm, am in [(1, 2), (2, 3), (3, 5), (5, 5), (5, 10), (10, 10), (10, 20)]:
            k = [r for r in rows if r[0] >= qm and r[1] >= am]
            print(f"问题≥{qm:2d} 回答≥{am:2d}：{len(k):5d} 题，回答共 {sum(r[2] for r in k) / 1000:.0f}k 词")
        return
    path, book, qmin, amin = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    name = os.path.basename(path).replace(".zim", "")
    n = k = 0
    with open(os.path.join(X.OUT, name + ".jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for doc, title, (qs, qb, as_, ab) in pages(path):
            if qs < qmin or as_ < amin: continue
            qw = qb.split()
            text = f"{X.H}{title}\nQuestion: {' '.join(qw[:Q_WORDS])}{' …' if len(qw) > Q_WORDS else ''}\n\nBest answer ({as_} votes): {ab}"
            k += 1
            for c in X.chunks(text, book, 3):
                f.write(json.dumps(dict(src=name, doc=doc, title=title, text=c), ensure_ascii=False) + "\n"); n += 1
    print(name, k, "题", n, "段")


if __name__ == "__main__":
    main()
