for f in /mnt/f/ARK/bench/models/gemma-4-E2B-litertlm/gemma-4-E2B-it.litertlm /mnt/f/ARK/train/export/e2b-base-oct.litertlm; do
  echo "======== $f"
  ~/ark/.venv/bin/python /mnt/f/ARK/train/wsl/peek.py $f 2>/dev/null | grep -E '^Section|Data Type|  Key:|Begin Offset|End Offset' | grep -v 'Key: (uuid|creation)' | paste - - - - - - 2>/dev/null | sed 's/  */ /g' | cut -c1-330
done
