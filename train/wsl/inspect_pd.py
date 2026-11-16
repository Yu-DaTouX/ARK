# 看 .litertlm 里主模型 decode 子图：算子统计、FC 权重位宽、KV 缓存形状
import io, sys, collections
from litert_lm_builder import litertlm_peek as P, litertlm_core as C
from litert_lm_builder import tflite_schema_py_generated as S
f = sys.argv[1]
md = P.read_litertlm_header(f, io.StringIO()); sm = md.SectionMetadata()
names = {v: k for k, v in S.BuiltinOperator.__dict__.items() if not k.startswith("_")}
tnames = {v: k for k, v in S.TensorType.__dict__.items() if not k.startswith("_")}
for i in range(sm.ObjectsLength()):
    o = sm.Objects(i)
    kv = {d["key"]: d["value"] for d in (P.kvp_to_dict(o.Items(j)) for j in range(o.ItemsLength()))}
    if kv.get("model_type") != "tf_lite_prefill_decode" or C.any_section_data_type_to_string(o.DataType()) != "TFLiteModel":
        continue
    with open(f, "rb") as fh:
        fh.seek(o.BeginOffset()); buf = fh.read(o.EndOffset() - o.BeginOffset())
    m = S.Model.GetRootAs(buf, 0)
    for k in range(m.SignatureDefsLength()):
        sd = m.SignatureDefs(k); key = sd.SignatureKey().decode()
        sg = m.Subgraphs(sd.SubgraphIndex())
        if "decode" not in key and k != m.SignatureDefsLength() - 1:
            continue
        print("== 签名", key, "子图", sd.SubgraphIndex(), "算子", sg.OperatorsLength())
        ins = []
        for x in range(sd.InputsLength()):
            t = sg.Tensors(sd.Inputs(x).TensorIndex()); ins.append(f"{sd.Inputs(x).Name().decode()}{list(t.ShapeAsNumpy())}")
        print("  输入", ins[:6], "...", ins[-3:], "共", len(ins))
        ops = collections.Counter(); fc = collections.Counter()
        def walk(g, depth=0):
            for z in range(g.OperatorsLength()):
                op = g.Operators(z); oc = m.OperatorCodes(op.OpcodeIndex())
                code = max(oc.BuiltinCode(), oc.DeprecatedBuiltinCode())
                nm = names.get(code, str(code))
                if nm == "CUSTOM" or nm == "STABLEHLO_COMPOSITE":
                    nm += ":" + (oc.CustomCode().decode() if oc.CustomCode() else "")
                ops[nm] += 1
                if nm == "FULLY_CONNECTED":
                    w = g.Tensors(op.Inputs(1)); q = w.Quantization()
                    nsc = q.ScaleLength() if q else 0
                    fc[(tnames.get(w.Type()), "per-channel" if nsc > 1 else "per-tensor", tuple(w.ShapeAsNumpy()))] += 1
        walk(sg)
        print("  算子:", dict(ops.most_common(25)))
        print("  FC 权重:", dict(fc.most_common(12)))
