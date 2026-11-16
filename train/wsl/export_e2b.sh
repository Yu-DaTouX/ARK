cd ~/ark && source .venv/bin/activate
OUT=/mnt/f/ARK/train/export/${1:-e2b-base}
SRC=${2:-/mnt/f/ARK/models/hf/gemma-4-E2B-it}
RECIPE=${3:-mixed48_b32}  # 主模型 4 bit 分块（按通道 int4 的 PTQ 会把 E2B 弄坏）
mkdir -p $OUT
time litert-torch export_hf $SRC $OUT \
  --task=text_generation --prefill_lengths=256,2048 --cache_length=4096 \
  --quantization_recipe=/mnt/f/ARK/train/wsl/$RECIPE.json --externalize_embedder=True --apply_gpu_composites=True \
  --export_vision_encoder=False --export_audio_encoder=False --model_dtype=float32 --experimental_lightweight_conversion=True 2>&1 \
  | grep -v -E "^(WARNING|I0000|W0000|\[ERROR\] .* is part of)" 
ls -la $OUT
