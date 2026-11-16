# 列出 .litertlm 各段及元数据（比较官方文件和自己导出的）
import io, re, sys
import litert_lm_builder as B
s = io.StringIO()
B.peek_litertlm_file(sys.argv[1], None, s)
print("\n".join(l for l in s.getvalue().splitlines() if not re.search(r"stop_token|^\s*\d+,?$", l))[:6000])
