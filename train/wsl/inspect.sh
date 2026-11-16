#!/bin/bash
for f in "$@"; do echo "######## $f"; ~/ark/.venv/bin/python /mnt/f/ARK/train/wsl/inspect_pd.py $f 2>&1 | grep -v Deprec | cut -c1-1500; done
