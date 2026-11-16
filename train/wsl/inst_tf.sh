cd ~/ark && source .venv/bin/activate
~/.local/bin/uv pip install -q tensorflow-cpu 2>&1 | tail -3
python -c "import tensorflow as tf; print('tf', tf.__version__)" 2>&1 | tail -1
litert-torch export_hf --help 2>&1 | grep -v -E "^(WARNING|I0000|\[ERROR\])" | head -120
