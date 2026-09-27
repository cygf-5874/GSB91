#!/usr/bin/env bash
# 跑既有用例（15 个）。跨平台处理 classpath 分隔符：Windows 是 ';'，类 Unix 是 ':'。
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/build.sh

CPSEP=':'
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
esac

exec java -Dfile.encoding=UTF-8 -cp "out${CPSEP}out-test" opvm.VmTest
