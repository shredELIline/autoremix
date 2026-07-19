#!/usr/bin/env sh
set -eu

build_dir="${1:-build/audio-core-benchmark}"
cmake -S audio-core -B "$build_dir" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DAUTOREMIX_AUDIO_CORE_BUILD_TESTS=OFF \
  -DAUTOREMIX_AUDIO_CORE_BUILD_BENCHMARKS=ON
cmake --build "$build_dir" --config Release --parallel
"$build_dir/autoremix_audio_core_benchmark"
