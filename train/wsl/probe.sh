P=~/ark/.venv/lib/python3.12/site-packages/litert_torch
sed -n 185,215p $P/_convert/core.py
echo ----; sed -n 514,560p $P/generative/export_hf/core/export_lib.py
echo ----; grep -n -A6 "use_v2:\|delete_in_memory_params:" $P/generative/export_hf/export.py | sed -n 1,30p | grep -v '^\s*$' | tail -14
