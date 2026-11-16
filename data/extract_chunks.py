# -*- coding: utf-8 -*-
"""把 ZIM 资料包（里面的 HTML 文章或 PDF）和散装 PDF 切成英文段落，写成 jsonl。

用法：python extract_chunks.py <zim或pdf目录> [...]
  环境变量 ARK_ONLY=<标题清单.txt>：ZIM 里只取清单里的 HTML 条目（如 mdwiki 核心集）；ARK_NAME 改输出文件名
输出：F:/ARK/data/chunks/<资料名>.jsonl，每行 {src, doc, title, text}
按章节切段：认出标题（HTML 的 h1–h4；PDF 里独占一行的全大写标题和 "5. IMPROVISED COMPASSES." 这类编号标题），
在标题处切开；每段正文前加一行“书名 › 章节”，检索和模型都能看出这段讲什么。
一节太长按段落边界切成约 120–380 词；PDF 的页眉页脚、页码、图题跳过；参考文献等章节丢掉。
PDF 的真实书名来自 book_titles.json（值为 null 的书不收），没列的用 ZIM 里 database.js 的标题。
这一步同时服务两件事：App 的段落级全文检索索引，以及微调用的问答素材。
"""
import ast, html, io, json, os, re, sys
from html.parser import HTMLParser

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("ARK_OUT", os.path.join(HERE, "chunks"))
MIN_W, MAX_W = 120, 380
SEC_MIN = int(os.environ.get("ARK_SEC_MIN", "120"))  # 遇到新标题时，当前段至少这么多词才单独成段，否则并进新的一节（30 时段落太碎，检索变差）
TAIL_MIN = 20  # 文档或章节末尾的零头，至少这么多词才保留
TITLES = json.load(open(os.path.join(HERE, "book_titles.json"), encoding="utf-8"))
H = "\x01"     # HTML 标题行的标记


class Text(HTMLParser):
    """极简 HTML 转文本：丢掉 script/style/导航，块级标签换行，h1–h4 标成标题行。"""
    SKIP = {"script", "style", "nav", "header", "footer", "noscript", "svg", "figure", "table"}
    BLOCK = {"p", "div", "li", "h1", "h2", "h3", "h4", "h5", "br", "tr", "section", "blockquote", "pre", "dd", "dt"}
    HEAD = {"h1", "h2", "h3", "h4"}

    def __init__(self):
        super().__init__(); self.out, self.skip, self.title, self._t = [], 0, "", False

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP: self.skip += 1
        if tag == "title": self._t = True
        if tag in self.BLOCK: self.out.append("\n")
        if tag in self.HEAD and not self.skip: self.out.append(H)

    def handle_endtag(self, tag):
        if tag in self.SKIP and self.skip: self.skip -= 1
        if tag == "title": self._t = False
        if tag in self.BLOCK: self.out.append("\n")

    def handle_data(self, d):
        if self._t: self.title += d
        elif not self.skip: self.out.append(d.replace("\n", " ") if self.out and self.out[-1].startswith(H) else d)


def html_text(raw):
    p = Text(); p.feed(raw)
    text = html.unescape("".join(p.out))
    text = re.sub(r"This article is issued from .*?(?:media files\.|$)", "", text, flags=re.S)  # Kiwix 加在维基条目末尾的声明
    return p.title.strip(), text


def pdf_text(data):
    from pypdf import PdfReader
    try:
        r = PdfReader(io.BytesIO(data))
        return "\n\n".join((pg.extract_text() or "") for pg in r.pages)
    except Exception:
        return ""


SMALL = set("a an and as at but by for from in into of on or the to with vs via".split())
NOHEAD = {"NOTE", "NOTES", "CAUTION", "WARNING", "WARNINGS", "DANGER", "FIGURE", "TABLE", "DISTRIBUTION", "DESTRUCTION",
          "CONTENTS", "INDEX", "GLOSSARY", "CHAPTER", "SECTION", "PART", "LESSON", "PREFACE", "FOREWORD", "ACKNOWLEDGEMENTS", "ACKNOWLEDGMENTS", "COPYRIGHT"}
SKIP_SECTIONS = {"references", "external links", "see also", "further reading", "notes", "sources", "bibliography",
                 "footnotes", "citations", "index", "glossary", "contents", "table of contents", "acknowledgements",
                 "acknowledgments"}
# 编号标题："5. IMPROVISED COMPASSES  .  There are three…"（标题全大写，后面可以紧跟正文）
NUMHEAD = re.compile(r"^(?:\d{1,2}|[IVX]{1,4})\.\s+([A-Z][A-Z0-9 ,'’&/()\-]{2,60}?[A-Z)])\s*(?:\.\s*(.*))?$")
# 页眉页脚、页码、图题："FM 3-05.70"、"8-38"、"A5-"、"9"、"Figure 8-27. Skinning Small Game"、"CHAPTER 8"
JUNK = re.compile(r"^(?:(?:FM|TC|GTA|MCRP|ATP|MCWP)\s*[\d-]+(?:\.\d+)?|[A-Z]?\d{1,3}(?:-\d{1,3})?-?|Figure \d.*|Page \d+(?: of \d+)?"
                  r"|CHAPTER \d+|Chapter \d+)$", re.I)
# 新段落的开头：条款号 8-77.、(1)、a.、项目符号
PARA = re.compile(r"^(?:\d{1,2}-\d{1,3}\.\s|\(?\d{1,2}\)\s|\(?[a-z]\)\s|[a-z]\.\s|[•▪◦●■*]\s?|[-–]\s)")


def pretty(s):
    s = re.sub(r"\s+", " ", s).strip(" .:,;-–")
    letters = [c for c in s if c.isalpha()]
    if letters and sum(c.isupper() for c in letters) > 0.8 * len(letters):  # 全大写改成标题格式
        s = " ".join(w if i and w in SMALL else w[:1].upper() + w[1:] for i, w in enumerate(s.lower().split()))
    return s


def heading(s):
    """PDF 里独占一行的全大写标题，如 'SKINNING AND BUTCHERING GAME'。"""
    s = s.strip().rstrip(" .:")
    if not 4 <= len(s) <= 70 or re.search(r"\d", s) or len(s.split()) > 9:
        return None
    letters = [c for c in s if c.isalpha()]
    if len(letters) < 4 or sum(c.isupper() for c in letters) < 0.9 * len(letters):
        return None
    if s.split()[0].rstrip(":") in NOHEAD:
        return None
    return pretty(s)


def blocks(text):
    """原文 → [(标题, None) 或 (None, 段落)]。PDF 的硬换行会接回成句子。"""
    para = []

    def flush():
        p = re.sub(r"\s+", " ", " ".join(para)).strip(); para.clear()
        return [(None, p)] if p else []

    for raw in text.split("\n"):
        s = raw.strip()
        if not s:
            yield from flush(); continue
        if s.startswith(H):
            yield from flush()
            if s[1:].strip(): yield pretty(s[1:]), None
            continue
        if JUNK.match(s) or sum(c.isalpha() for c in s) < 2:
            continue
        h = heading(s)
        if h:
            yield from flush(); yield h, None; continue
        m = NUMHEAD.match(s)
        if m and len(re.sub(r"[^A-Z]", "", m.group(1))) >= 4:
            yield from flush(); yield pretty(m.group(1)), None
            s = (m.group(2) or "").strip()
            if not s: continue
        if para and (PARA.match(s) or (re.search(r"[.!?:;\"”)]$", para[-1]) and s[0].isupper())):
            yield from flush()
        para.append(s)
    yield from flush()


def good(par, min_w):
    words = par.split()
    if len(words) < min_w: return False
    letters = sum(c.isalpha() for c in par)
    return letters / max(1, len(par)) > 0.6 and not re.search(r"(\.{5,}|…{3,}|(\. ){5,})", par)  # 去掉目录页的点线


def chunks(text, book, min_w=8):
    """按章节切段，每段前面加“书名 › 章节”。"""
    sec, skip, head, buf, n = "", False, "", [], 0

    def out():
        pre = book + (f" › {head}" if head and head.lower() not in book.lower() else "")
        return pre + "\n" + " ".join(buf)

    for h, p in blocks(text):
        if h is not None:
            skip = h.lower() in SKIP_SECTIONS
            if n >= SEC_MIN or (skip and n >= TAIL_MIN):
                yield out()
            if n >= SEC_MIN or skip:
                buf, n = [], 0
            sec = h
            if buf: buf.append(h + ".")  # 上一节只剩零头：并进新的一节，标题留在正文里
            head = h
            continue
        if skip or not good(p, min_w):
            continue
        w = len(p.split())
        if n + w > MAX_W and n >= MIN_W:
            yield out()
            buf, n, head = [], 0, sec
        if not buf: head = sec
        buf.append(p); n += w
        if n > MAX_W:  # 单段过长就硬切
            words = " ".join(buf).split()
            while len(words) > MAX_W:
                buf = words[:MAX_W]; yield out(); words = words[MAX_W:]
            buf, n = [" ".join(words)], len(words)
    if n >= TAIL_MIN:
        yield out()


def zim_titles(a):
    try:
        t = bytes(a.get_entry_by_path("database.js").get_item().content).decode("utf-8", "replace")
        return {fp: r["ti"] for r in ast.literal_eval(t[t.index("["):t.rindex("]") + 1]) for fp in r["fp"]}
    except Exception:
        return {}


def from_zim(path, name):
    from libzim.reader import Archive
    a = Archive(path)
    db = zim_titles(a)
    only = os.environ.get("ARK_ONLY")
    only = {l.strip().lower() for l in open(only, encoding="utf-8") if l.strip() and not l.startswith("#")} if only else None
    for i in range(a.all_entry_count):
        e = a.get_entry_by_id(i)
        if e.is_redirect: continue
        it = e.get_item()
        if it.mimetype.startswith("text/html"):
            if only is not None and (e.title or "").lower() not in only: continue
            if re.match(r"(Module|Template|Category|File|Help|Special|User|Talk|MediaWiki|Wikiciv|Glossary):", e.title or ""): continue
            title, text = html_text(bytes(it.content).decode("utf-8", "ignore"))
            yield e.path, e.title or title, text, 3
        elif it.mimetype == "application/pdf":
            if only is not None: continue  # 按清单挑条目时，ZIM 里附带的 PDF（如 energypedia 的项目报告）不收
            fn = os.path.basename(e.path)
            book = TITLES.get(f"{name}/{fn}", db.get(fn, fn))
            if book is None: continue
            yield e.path, book, pdf_text(bytes(it.content)), 8


def from_pdf_dir(path, name):
    """一个目录 = 一本书（目录名是书名），里面的 PDF、HTML（古登堡 HTML 版，去掉版权页头页尾）或 TXT / Markdown（# 标题当章节）。"""
    book = name.replace("_", " ")
    many = sum(f.lower().endswith((".html", ".htm")) for f in os.listdir(path)) > 1  # 一目录多篇文章（如维基百科精选）：用各篇自己的标题
    for fn in sorted(os.listdir(path)):
        p = os.path.join(path, fn)
        if fn.lower().endswith(".pdf"):
            yield fn, book, pdf_text(open(p, "rb").read()), 8
        elif fn.lower().endswith((".html", ".htm")):
            t, text = html_text(open(p, encoding="utf-8", errors="ignore").read())
            m = re.search(r"\*\*\*\s*START OF TH(?:E|IS) PROJECT GUTENBERG.*?\*\*\*", text, re.S)
            e = re.search(r"\*\*\*\s*END OF TH(?:E|IS) PROJECT GUTENBERG", text)
            yield fn, (t or fn[:-5]) if many else book, text[m.end() if m else 0:e.start() if e else None], 3
        elif fn.lower().endswith((".txt", ".md")):
            text = open(p, encoding="utf-8", errors="ignore").read()
            yield fn, book, re.sub(r"(?m)^#{1,4}\s+", H, text), 3


def main():
    os.makedirs(OUT, exist_ok=True)
    for src in sys.argv[1:]:
        name = os.path.basename(src.rstrip("/\\")).replace(".zim", "")
        gen = from_zim(src, name) if src.endswith(".zim") else from_pdf_dir(src, name)
        n = 0
        with open(os.path.join(OUT, os.environ.get("ARK_NAME", name) + ".jsonl"), "w", encoding="utf-8", newline="\n") as f:
            for doc, title, text, min_w in gen:
                for c in chunks(text, title, min_w):
                    f.write(json.dumps(dict(src=name, doc=doc, title=title, text=c), ensure_ascii=False) + "\n"); n += 1
        print(name, n, "段")


if __name__ == "__main__":
    main()
