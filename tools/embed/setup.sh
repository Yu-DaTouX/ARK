#!/usr/bin/env bash
# 下载电脑端向量 / 生成工具需要的文件（不进 Git）：
#   litertlm-jvm.jar  LiteRT-LM 的 JVM 包（Google Maven，内含 Windows / Linux / macOS 原生库），版本和 App 一致
#   EmbeddingGemma 2 270M 向量模型（Hugging Face，Apache-2.0），和手机上用的是同一个文件
set -e
cd "$(dirname "$0")"
V=0.19.1
[ -f litertlm-jvm.jar ] || curl -L -o litertlm-jvm.jar \
  "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-jvm/$V/litertlm-jvm-$V.jar"
mkdir -p ../../models/embed
M=../../models/embed/embeddinggemma-2-text-270m.litertlm
[ -f "$M" ] || curl -L -o "$M" \
  "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm"
echo "完成。编译：gradle installDist（JDK 21）；之后用 build/install/ark-embed/bin/ark-embed"
