# 列出 .litertlm 里各 TFLite 段的签名（输入/输出名和形状），比较官方文件与自导出的接口
import io, sys
from litert_lm_builder import litertlm_peek as P, litertlm_core as C
from litert_lm_builder import tflite_schema_py_generated as S
f = sys.argv[1]
md = P.read_litertlm_header(f, io.StringIO())
sm = md.SectionMetadata()
with open(f, "rb") as fh:
    for i in range(sm.ObjectsLength()):
        o = sm.Objects(i)
        kv = {d["key"]: d["value"] for d in (P.kvp_to_dict(o.Items(j)) for j in range(o.ItemsLength()))}
        if C.any_section_data_type_to_string(o.DataType()) != "TFLiteModel" or (len(sys.argv) > 2 and kv.get("model_type") not in sys.argv[2:]):
            continue
        fh.seek(o.BeginOffset()); buf = fh.read(min(o.EndOffset() - o.BeginOffset(), 64 << 20))
        try:
            m = S.Model.GetRootAs(buf, 0)
        except Exception as e:
            print(i, kv.get("model_type"), "读不了", e); continue
        print(f"== {i} {kv.get('model_type')}  子图 {m.SubgraphsLength()}")
        for k in range(m.SignatureDefsLength()):
            sd = m.SignatureDefs(k); sg = m.Subgraphs(sd.SubgraphIndex())
            def desc(t):
                tt = sg.Tensors(t.TensorIndex()); return f"{t.Name().decode()}{list(tt.ShapeAsNumpy()) if tt.ShapeLength() else []}"
            ins = [desc(sd.Inputs(x)) for x in range(sd.InputsLength())]
            outs = [desc(sd.Outputs(x)) for x in range(sd.OutputsLength())]
            kvc = [x for x in ins if "kv_cache" in x]
            print(f"  [{sd.SignatureKey().decode()}] in: {[x for x in ins if 'kv_cache' not in x]} + {len(kvc)} kv（例 {kvc[:1]}）")
            print(f"      out: {[x for x in outs if 'kv_cache' not in x][:8]} + {sum('kv_cache' in x for x in outs)} kv")
