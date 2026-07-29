#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${script_dir}/build"

cmake -S "${script_dir}" -B "${build_dir}" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build "${build_dir}" --parallel
ctest --test-dir "${build_dir}" --output-on-failure

echo "Native FredPlayer built successfully."
echo "Manual test command: ${build_dir}/fredplayer-native"
