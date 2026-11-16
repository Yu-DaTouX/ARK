#!/usr/bin/env bash
# 按顺序评测所有候选模型；模型还在下载就等它下完
cd /f/ARK/eval
G=F:/ARK/models/gguf
M="qwen3.5-2b:qwen3.5-2b.Q4_K_M.gguf minicpm5-2b:minicpm5-2b.Q4_K_M.gguf qwen3.5-4b:qwen3.5-4b.Q4_K_M.gguf granite4.2-3b:granite4.2-3b.Q4_K_M.gguf lfm3-1.7b:lfm3-1.7b.Q4_K_M.gguf gemma4-e2b:gemma4-e2b.qat-Q4_0.gguf unc-hauhau-qwen3.5-2b:unc-hauhau-qwen3.5-2b.Q4_K_M.gguf unc-hauhau-qwen3.5-4b:unc-hauhau-qwen3.5-4b.Q4_K_M.gguf unc-huihui-qwen3.5-4b:unc-huihui-qwen3.5-4b.Q4_K_M.gguf unc-huihui-qwen3.5-2b:unc-huihui-qwen3.5-2b.Q4_K_M.gguf unc-heretic-gemma4-e2b:unc-heretic-gemma4-e2b.Q4_K_M.gguf gemma4-e4b:gemma4-e4b.qat-Q4_0.gguf unc-hauhau-gemma4-e4b:unc-hauhau-gemma4-e4b.Q4_K_M.gguf qwen3.5-9b:qwen3.5-9b.Q4_K_M.gguf"
for m in $M; do
  n=${m%%:*}; f=$G/${m#*:}
  while [ ! -f "$f" ] || [ -f "$f.aria2" ]; do sleep 30; done
  python run_eval.py "$n=$f" 2>&1 | tail -2
done
echo "ALL DONE"
