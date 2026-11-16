#!/bin/bash
# 用法：requant.sh <导出名> <配方名>   → 重新量化主模型并打包成 /mnt/f/ARK/train/export/<导出名>-<配方名>.litertlm
D=$(ls -d /mnt/f/ARK/train/export/$1/tmp* | head -1)
cd /mnt/f/ARK/train/wsl
{ time ~/ark/.venv/bin/python requant.py "$D" $2 && ~/ark/.venv/bin/python pack_litertlm.py "$D" /mnt/f/ARK/train/export/$1-$2.litertlm model_$2.tflite; } > /mnt/f/ARK/train/requant_$1_$2.log 2>&1
