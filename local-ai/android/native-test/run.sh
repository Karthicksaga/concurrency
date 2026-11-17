#!/usr/bin/env bash
# Builds la_jni.cpp + llama.cpp for the host (x86_64/arm64 Linux or macOS) and runs the JNI engine
# against a real GGUF model: load, two-turn chat with KV reuse, abort, overflow, special tokens, UTF-8.
# Usage: local-ai/android/native-test/run.sh [path/to/model.gguf]   (default: downloads Qwen3 0.6B, 610 MB)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../app/src/main/cpp"
WORK="${WORK:-$HERE/build}"
MODEL="${1:-$WORK/Qwen3-0.6B-Q8_0.gguf}"
mkdir -p "$WORK"
if [ ! -f "$MODEL" ]; then
  curl -fL -o "$MODEL" https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf
  echo "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031  $MODEL" | sha256sum -c -
fi
cmake -S "$HERE" -B "$WORK/cmake" -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_SRC="$CPP/llama.cpp" -DJNI_SRC="$CPP/la_jni.cpp"
cmake --build "$WORK/cmake" -j"$(nproc 2>/dev/null || echo 4)"
cp "$WORK/cmake/liblocalai.so" "$WORK/cmake/bin/"
javac -d "$WORK/classes" "$HERE/java/com/localai/app/runtime/inference/LlamaNative.java" "$HERE/java/HostTest.java"
LD_LIBRARY_PATH="$WORK/cmake/bin" java -cp "$WORK/classes" HostTest "$WORK/cmake/bin" "$MODEL"
