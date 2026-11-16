#!/bin/bash
# 用和 App 相同的运行时与参数（贪心、关闭思考、900 token）在电脑 CPU 上让候选模型回答 40 道评测题。
#   bash gen_cand.sh <模型.litertlm> <输出名>     → F:/ARK/train/sft/pc_<输出名>.jsonl
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export JAVA_OPTS="-Djava.library.path=F:/ARK/tools/embed/native"
export ARK_EMBED_CPU=${ARK_EMBED_CPU-1}
/f/ARK/tools/embed/build/install/ark-embed/bin/ark-embed gen "$1" F:/ARK/train/sft/gen_in.jsonl "F:/ARK/train/sft/pc_$2.jsonl" 900
