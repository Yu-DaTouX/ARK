# 把 litert-torch export_hf 留下的中间目录打包成 .litertlm（导出最后一步因 litert_lm_builder 0.19.2 改了 API 而失败时用）
#   python pack_litertlm.py <tmp 目录> <输出.litertlm> [主模型文件名，默认 model_quantized.tflite]
import os, sys

import litert_lm_builder as B
from litert_lm_builder.runtime.proto import llm_metadata_pb2 as M

src, dst = sys.argv[1], sys.argv[2]
T = M.LlmMetadata
p = lambda n: os.path.join(src, n)
b = B.LitertLmFileBuilder()
b.add_system_metadata(B.Metadata(key="Authors", value="ODML", dtype=B.DType.STRING))
b.add_llm_metadata(p("llm_metadata.pb"))
if os.path.exists(p("executor_metadata.pb")):
    b.add_executor_metadata(p("executor_metadata.pb"))
b.add_hf_tokenizer(p("tokenizer.json"))
# 官方文件给主模型标了 fp16 激活（GPU 上解码更快）；ARK_FP32=1 时不标
fp16 = {} if os.environ.get("ARK_FP32") else {"prefer_activation_type": "fp16"}
b.add_tflite_model(p(sys.argv[3] if len(sys.argv) > 3 else "model_quantized.tflite"), T.TF_LITE_PREFILL_DECODE, **fp16)
b.add_tflite_model(p("embedder_quantized.tflite"), T.TF_LITE_EMBEDDER)
b.add_tflite_model(p("per_layer_embedder_quantized.tflite"), T.TF_LITE_PER_LAYER_EMBEDDER)
with open(dst, "wb") as f:
    b.build(f)
print("写好", dst, os.path.getsize(dst) // 2**20, "MB")
