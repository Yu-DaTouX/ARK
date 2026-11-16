# 打印官方主模型 decode 子图里前 N 个算子（含输入/输出张量类型和名字尾部），看一层的完整接线
import io, sys
from litert_lm_builder import litertlm_peek as P, litertlm_core as C
from litert_lm_builder import tflite_schema_py_generated as S
f, N = sys.argv[1], int(sys.argv[2]); A0 = int(__import__("os").environ.get("ARK_FROM", "0"))
sig = sys.argv[3] if len(sys.argv) > 3 else "decode"
md = P.read_litertlm_header(f, io.StringIO()); sm = md.SectionMetadata()
names = {v: k for k, v in S.BuiltinOperator.__dict__.items() if not k.startswith("_")}
tn = {v: k for k, v in S.TensorType.__dict__.items() if not k.startswith("_")}
for i in range(sm.ObjectsLength()):
    o = sm.Objects(i)
    kv = {d["key"]: d["value"] for d in (P.kvp_to_dict(o.Items(j)) for j in range(o.ItemsLength()))}
    if kv.get("model_type") != "tf_lite_prefill_decode" or C.any_section_data_type_to_string(o.DataType()) != "TFLiteModel":
        continue
    with open(f, "rb") as fh:
        fh.seek(o.BeginOffset()); buf = fh.read(o.EndOffset() - o.BeginOffset())
    m = S.Model.GetRootAs(buf, 0)
    print("签名:", [(m.SignatureDefs(k).SignatureKey().decode(), m.SignatureDefs(k).SubgraphIndex()) for k in range(m.SignatureDefsLength())])
    sd = [m.SignatureDefs(k) for k in range(m.SignatureDefsLength()) if m.SignatureDefs(k).SignatureKey().decode() == sig][0]
    g = m.Subgraphs(sd.SubgraphIndex())
    def t(ix):
        if ix < 0: return "-"
        tt = g.Tensors(ix); q = tt.Quantization()
        nm = tt.Name().decode()
        return f"#{ix}{tn.get(tt.Type())}{[int(x) for x in tt.ShapeAsNumpy()] if tt.ShapeLength() else []}{'q' if q and q.ScaleLength() else ''}:{nm[-38:]}"
    for z in range(A0, min(N, g.OperatorsLength())):
        op = g.Operators(z); oc = m.OperatorCodes(op.OpcodeIndex())
        nm = names.get(max(oc.BuiltinCode(), oc.DeprecatedBuiltinCode()))
        if nm == "STABLEHLO_COMPOSITE":
            bo = op.BuiltinOptions2() if hasattr(op, "BuiltinOptions2") else None
            try:
                co = S.StableHLOCompositeOptions(); co.Init(bo.Bytes, bo.Pos); nm += ":" + co.Name().decode()
            except Exception:
                pass
        ins = [t(op.Inputs(k)) for k in range(op.InputsLength())]
        outs = [t(op.Outputs(k)) for k in range(op.OutputsLength())]
        print(z, nm, "IN", [x for x in ins if "kv_cache" not in x][:4], "OUT", outs[:2])
    break
