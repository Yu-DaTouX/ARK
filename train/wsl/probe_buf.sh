#!/bin/bash
~/ark/.venv/bin/python /mnt/f/ARK/train/wsl/probe_buf.py "$@" 2>&1 | grep -v Deprec
