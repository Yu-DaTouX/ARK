# -*- coding: utf-8 -*-
"""把 errata.json 里的“现行指南提醒”作为前缀写进资料包 chunks.text。

用法:
    python data/apply_errata.py <包路径> [--errata data/errata.json] [--dry-run] [--remove]

- 只改 chunks.text：不碰 text_en、FTS（chunks_fts 是 contentless 表，不随 text 变化）和向量表。
- 可重复运行：写入前先剥掉已有的提醒前缀再按当前 errata.json 重写，所以不会重复叠加；
  修改了 note 后重跑会更新为新内容；从 errata.json 删掉的 id 会自动去掉旧前缀。
- 同一 id 出现在多条 errata 中时，每条提醒各占一行，前缀块末尾空一行。
- --remove：只剥离所有提醒前缀，不写新前缀（还原）。
"""
import argparse
import json
import os
import sqlite3
import sys

MARK = '【现行指南提醒】'


def strip_prefix(text):
    """去掉文本开头的提醒块（若干以 MARK 开头的行 + 一个空行）。"""
    if text is None or not text.startswith(MARK):
        return text
    end = text.find('\n\n')
    if end < 0:  # 理论上不会出现：只有前缀没有正文
        return ''
    return text[end + 2:]


def build_prefix(notes):
    lines = []
    for n in notes:
        n = ' '.join(n.split())  # 提醒内不允许换行，保证前缀块可被准确识别
        lines.append(MARK + n)
    return '\n'.join(lines) + '\n\n'


def load_errata(path):
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    by_id = {}
    for i, e in enumerate(data):
        note = (e.get('note') or '').strip()
        ids = e.get('ids') or []
        if not note or not ids:
            raise SystemExit(f'errata 第 {i} 条缺少 note 或 ids')
        for cid in ids:
            lst = by_id.setdefault(int(cid), [])
            if note not in lst:
                lst.append(note)
    return by_id


def main():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('pack', help='资料包路径（.arkpack，sqlite）')
    ap.add_argument('--errata', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'errata.json'))
    ap.add_argument('--dry-run', action='store_true', help='只统计不写入')
    ap.add_argument('--remove', action='store_true', help='剥离全部提醒前缀，不写新前缀')
    args = ap.parse_args()

    if not os.path.isfile(args.pack):
        raise SystemExit(f'找不到资料包: {args.pack}')
    by_id = {} if args.remove else load_errata(args.errata)

    db = sqlite3.connect(args.pack)
    try:
        # 需要处理的行 = errata 指定的 id ∪ 当前已带前缀的行（以便清理过期提醒）
        existing = {r[0] for r in db.execute("SELECT id FROM chunks WHERE substr(text, 1, ?) = ?", (len(MARK), MARK))}
        targets = sorted(set(by_id) | existing)
        updated = unchanged = 0
        missing = []
        cur = db.cursor()
        cur.execute('BEGIN')
        for cid in targets:
            row = cur.execute('SELECT text FROM chunks WHERE id = ?', (cid,)).fetchone()
            if row is None:
                missing.append(cid)
                continue
            old = row[0] or ''
            body = strip_prefix(old)
            new = (build_prefix(by_id[cid]) + body) if cid in by_id else body
            if new == old:
                unchanged += 1
                continue
            updated += 1
            if not args.dry_run:
                cur.execute('UPDATE chunks SET text = ? WHERE id = ?', (new, cid))
        if args.dry_run:
            db.rollback()
        else:
            db.commit()
    finally:
        db.close()

    mode = '（dry-run，未写入）' if args.dry_run else ''
    print(f'errata 涉及 {len(by_id)} 个段落；已带前缀 {len(existing)} 个；'
          f'更新 {updated}，无需改动 {unchanged}{mode}')
    if missing:
        print(f'警告：包中不存在的 id {len(missing)} 个: {missing}')


if __name__ == '__main__':
    main()
