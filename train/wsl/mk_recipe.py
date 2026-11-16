import json
from ai_edge_quantizer import recipe as r
T = r.TFLOperationName
rec = (r.dynamic_wi4c_afp32(operation_name=T.EMBEDDING_LOOKUP)
       + r.dynamic_wi4c_afp32(operation_name=T.FULLY_CONNECTED)
       + r.dynamic_wi8c_afp32(regex='per_layer', operation_name=T.FULLY_CONNECTED))
def enc(o):
    if hasattr(o, 'value'): return o.value
    if hasattr(o, 'to_dict'): return o.to_dict()
    if hasattr(o, '__dict__'): return o.__dict__
    return str(o)
s = json.dumps(rec, default=enc, indent=1)
open('/mnt/f/ARK/train/wsl/mixed48.json', 'w').write(s)
print(s[:1500])
