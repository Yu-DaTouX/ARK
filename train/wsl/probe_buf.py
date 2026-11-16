# 看官方 prefill_decode 段：缓冲区是内嵌还是 offset 外置；FC 的选项；schema 有没有对象 API
import io, sys
from litert_lm_builder import litertlm_peek as P, litertlm_core as C
from litert_lm_builder import tflite_schema_py_generated as S
f = sys.argv[1]
print("对象 API:", hasattr(S, "ModelT"), hasattr(S, "BufferT"))
md = P.read_litertlm_header(f, io.StringIO()); sm = md.SectionMetadata()
for i in range(sm.ObjectsLength()):
    o = sm.Objects(i)
    kv = {d["key"]: d["value"] for d in (P.kvp_to_dict(o.Items(j)) for j in range(o.ItemsLength()))}
    if kv.get("model_type") != "tf_lite_prefill_decode" or C.any_section_data_type_to_string(o.DataType()) != "TFLiteModel":
        continue
    print("段", i, o.BeginOffset(), o.EndOffset(), kv)
    with open(f, "rb") as fh:
        fh.seek(o.BeginOffset()); buf = fh.read(o.EndOffset() - o.BeginOffset())
    m = S.Model.GetRootAs(buf, 0)
    nb = m.BuffersLength(); off = sum(1 for k in range(nb) if m.Buffers(k).Offset() > 0)
    inl = sum(m.Buffers(k).DataLength() for k in range(nb))
    print("buffers", nb, "offset 外置", off, "内嵌字节", inl, "subgraphs", m.SubgraphsLength(), "version", m.Version())
    print("metadata", [m.Metadata(k).Name().decode() for k in range(m.MetadataLength())])
    g = m.Subgraphs(0)
    for z in range(g.OperatorsLength()):
        op = g.Operators(z); oc = m.OperatorCodes(op.OpcodeIndex())
        if max(oc.BuiltinCode(), oc.DeprecatedBuiltinCode()) == S.BuiltinOperator.FULLY_CONNECTED:
            o2 = S.FullyConnectedOptions(); bo = op.BuiltinOptions(); o2.Init(bo.Bytes, bo.Pos)
            w = g.Tensors(op.Inputs(1)); q = w.Quantization()
            print("FC#", z, "keep_num_dims", o2.KeepNumDims(), "wfmt", o2.WeightsFormat(), "asym", o2.AsymmetricQuantizeInputs(),
                  "w scale 个数", q.ScaleLength(), "zp0", q.ZeroPoint(0) if q.ZeroPointLength() else None, "qdim", q.QuantizedDimension(),
                  "opver", oc.Version())
            if z > 20: break
    break
