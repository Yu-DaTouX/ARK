#!/bin/bash
# 用法：repack.sh <导出名> <主模型文件名> <输出名>   （用已有的量化结果重新打包，不重新量化）
D=$(ls -d /mnt/f/ARK/train/export/$1/tmp* | head -1)
~/ark/.venv/bin/python /mnt/f/ARK/train/wsl/pack_litertlm.py "$D" /mnt/f/ARK/train/export/$3.litertlm $2 2>&1 | grep -v Deprec
~/ark/.venv/bin/python /mnt/f/ARK/train/wsl/sections.py /mnt/f/ARK/train/export/$3.litertlm 2>&1 | grep prefill
