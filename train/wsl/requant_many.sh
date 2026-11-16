#!/bin/bash
# 用法：requant_many.sh <导出名> <配方名...>   依次重新量化并打包
for r in "${@:2}"; do bash /mnt/f/ARK/train/wsl/requant.sh $1 $r; grep -v Deprecat /mnt/f/ARK/train/requant_$1_$r.log | tail -2; done
