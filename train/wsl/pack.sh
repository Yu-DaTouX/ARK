#!/bin/bash
# 用法：pack.sh <导出名>   → /mnt/f/ARK/train/export/<名>.litertlm
set -e
D=$(ls -d /mnt/f/ARK/train/export/$1/tmp* | head -1)
~/ark/.venv/bin/python /mnt/f/ARK/train/wsl/pack_litertlm.py "$D" /mnt/f/ARK/train/export/$1.litertlm
# 修好 litert_torch 里的旧 API（TfLiteModelType → proto 枚举），以后导出最后一步就不会再失败
F=~/ark/.venv/lib/python3.12/site-packages/litert_torch/generative/export_hf/core/litert_lm_builder.py
grep -q 'litertlm_builder.TfLiteModelType.' $F && sed -i.bak 's/litertlm_builder\.TfLiteModelType\./llm_metadata_pb2.LlmMetadata.TF_LITE_/g' $F && echo 已修补 litert_torch
~/ark/.venv/bin/python -c "import litert_lm_builder as B; B.peek_litertlm_file('/mnt/f/ARK/train/export/$1.litertlm')" 2>&1 | grep -i 'section\|model_type\|size' | head -30
