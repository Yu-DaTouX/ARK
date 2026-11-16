# 看主模型 decode 子图里前几个 FULLY_CONNECTED 的输入/输出接线：张量类型、由什么算子产生
import io, sys
from litert_lm_builder import litertlm_peek as P, litertlm_core as C
from litert_lm_builder import tflite_schema_py_generated as S
f = sys.argv[1]
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
    sd = [m.SignatureDefs(k) for k in range(m.SignatureDefsLength()) if m.SignatureDefs(k).SignatureKey().decode() == "decode"][0]
    g = m.Subgraphs(sd.SubgraphIndex())
    prod = {}
    opn = lambda op: names.get(max(m.OperatorCodes(op.OpcodeIndex()).BuiltinCode(), m.OperatorCodes(op.OpcodeIndex()).DeprecatedBuiltinCode()))
    for z in range(g.OperatorsLength()):
        op = g.Operators(z)
        for k in range(op.OutputsLength()): prod[op.Outputs(k)] = (z, opn(op))
    def t(ix):
        if ix < 0: return "-"
        tt = g.Tensors(ix); q = tt.Quantization()
        return f"{tn.get(tt.Type())}{list(tt.ShapeAsNumpy()) if tt.ShapeLength() else []}{' q' + str(q.ScaleLength()) if q and q.ScaleLength() else ''} <- {prod.get(ix, ('const/input',))[-1]}"
    n = 0
    for z in range(g.OperatorsLength()):
        op = g.Operators(z); nm = opn(op)
        if nm in ("FULLY_CONNECTED", "DEQUANTIZE", "QUANTIZE") and n < 14:
            n += 1
            ins = [t(op.Inputs(k)) for k in range(op.InputsLength())]
            outs = [t(op.Outputs(k)) for k in range(op.OutputsLength())]
            extra = ""
            if nm == "FULLY_CONNECTED":
                bo = op.BuiltinOptions()
                if bo:
                    fo = S.FullyConnectedOptions(); fo.Init(bo.Bytes, bo.Pos)
                    extra = f" keep_num_dims={fo.KeepNumDims()} asym={fo.AsymmetricQuantizeInputs()} qtype={fo.QuantizedBiasType() if hasattr(fo,'QuantizedBiasType') else ''}"
            print(z, nm, "IN", ins, "OUT", outs, extra)
    break
