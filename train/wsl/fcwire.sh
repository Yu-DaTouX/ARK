#!/bin/bash
for f in "$@"; do echo "######## $f"; ~/ark/.venv/bin/python /mnt/f/ARK/train/wsl/fcwire.py $f 2>&1 | grep -v Deprec | cut -c1-700; done
