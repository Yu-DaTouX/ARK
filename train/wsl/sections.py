# 列出 .litertlm 的各段：类型、大小、元数据（不打印内容）
import io, sys
from litert_lm_builder import litertlm_peek as P, litertlm_core as C
for f in sys.argv[1:]:
    print("========", f)
    md = P.read_litertlm_header(f, io.StringIO())
    sm = md.SectionMetadata()
    for i in range(sm.ObjectsLength()):
        o = sm.Objects(i)
        kv = {d["key"]: d["value"] for d in (P.kvp_to_dict(o.Items(j)) for j in range(o.ItemsLength()))}
        print(i, C.any_section_data_type_to_string(o.DataType()), f"{(o.EndOffset() - o.BeginOffset()) / 2**20:.0f} MB", kv)
