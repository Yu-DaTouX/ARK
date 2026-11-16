# 用别的量化配方重新量化导出目录里的 fp32 主模型（model.tflite），不用重新导出。
#   python requant.py <tmp 目录> <配方名>      → <tmp 目录>/model_<配方名>.tflite
#
# 背景：按通道 int4 + 最简单的 min/max 定标（c4）会把 E2B 弄坏（输出重复乱码）；
#       分块 int4（b32）质量好，但手机 GPU 跑出来全是 <pad>。只能在按通道量化里想办法。
# 配方名：
#   c4   4 bit 按通道，min/max 定标（原来的）      b32 / b64  4 bit 分块（手机 GPU 不支持）
#   mse  4 bit 按通道，按最小均方误差选截断        oct        4 bit 按通道，OCTAV 最优截断
#   c8   全 8 bit 按通道                          a8         注意力 8 bit、MLP 4 bit（MSE）
#   d8   MLP down_proj 8 bit、其余 4 bit（MSE）
import os, sys, time

from ai_edge_quantizer import quantizer, recipe_manager
from ai_edge_quantizer import algorithm_manager as A
from ai_edge_quantizer import qtyping as Q

FC = Q.TFLOperationName.FULLY_CONNECTED
CH, B32, B64 = Q.QuantGranularity.CHANNELWISE, Q.QuantGranularity.BLOCKWISE_32, Q.QuantGranularity.BLOCKWISE_64
MM, MSE, OCT = A.AlgorithmName.MIN_MAX_UNIFORM_QUANT, A.AlgorithmName.MSE, A.AlgorithmName.OCTAV

src, full = sys.argv[1], sys.argv[2]
# 前缀 w：只量化权重（插 DEQUANTIZE，算子用浮点算）。官方文件就是这种结构，手机 GPU 上快得多；
# 不带前缀：动态量化（运行时把激活也量化），手机 GPU 上解码只有官方的 1/3。
weight_only = full.startswith("w")
name = full[1:] if weight_only else full
rm = recipe_manager.RecipeManager()
cfg = rm.add_weight_only_config if weight_only else rm.add_dynamic_config
add = lambda regex, bits, gran=CH, alg=MM: cfg(regex=regex, operation_name=FC, num_bits=bits, granularity=gran, algorithm_key=alg)
if name in ("c4", "b32", "b64", "mse", "oct", "c8"):
    add(".*", 8 if name == "c8" else 4, {"b32": B32, "b64": B64}.get(name, CH), {"mse": MSE, "oct": OCT}.get(name, MM))
elif name == "a8":
    add(".*", 4, alg=MSE)
    add(".*(qkv_proj|o_proj);.*", 8)
elif name == "d8":
    add(".*", 4, alg=MSE)
    add(".*down_proj;.*", 8)
else:
    sys.exit(f"未知配方 {full}")
add("per_layer", 8)  # 每层嵌入的投影要 8 bit（官方配方的说明）
t0 = time.time()
q = quantizer.Quantizer(os.path.join(src, "model.tflite"))
q.load_quantization_recipe(rm.get_quantization_recipe())
dst = os.path.join(src, f"model_{full}.tflite")
q.quantize().export_model(dst, overwrite=True)
print("写好", dst, os.path.getsize(dst) // 2**20, "MB", f"{time.time() - t0:.0f}s")
