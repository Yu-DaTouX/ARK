#!/bin/bash
export ARK_FROM=${ARK_FROM:-0}
~/ark/.venv/bin/python /mnt/f/ARK/train/wsl/layer_ops.py "$@" 2>&1 | grep -v Deprec | cut -c1-330
