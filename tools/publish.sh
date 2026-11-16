#!/usr/bin/env bash
# 把当前 HEAD 同步成公开仓库的一个新提交（分支 public → GitHub main），排除 docs/public_exclude.txt 里的文件
# （这些评测 / 训练数据里含 Foxfire、Richard Graves 等不能再分发的原文）。本地 main 保留完整历史，不推到 GitHub。
#   bash tools/publish.sh "提交说明"
set -e
cd "$(dirname "$0")/.."
export GIT_INDEX_FILE="$(git rev-parse --git-dir)/public.index"
rm -f "$GIT_INDEX_FILE"
git read-tree HEAD
grep -v '^\s*$' docs/public_exclude.txt | tr '\n' '\0' | xargs -0 git rm -q --cached --ignore-unmatch
T=$(git write-tree)
P=$(git rev-parse -q --verify public || true)
[ -n "$P" ] && [ "$(git rev-parse "$P^{tree}")" = "$T" ] && { echo "没有变化"; exit 0; }
C=$(git commit-tree "$T" ${P:+-p "$P"} -m "${1:-更新}")
git branch -f public "$C"
rm -f "$GIT_INDEX_FILE"
git push github public:main
