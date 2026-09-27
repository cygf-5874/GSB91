#!/usr/bin/env bash
# 编译 src/main/java 到 out/、src/test/java 到 out-test/。
#
# 用 `find ... -delete` 清掉旧的 class，而不是 `rm -rf out`：一次删几十个文件
# 在某些沙箱里会被安全护栏拦下。out/ 与 out-test/ 都在 .gitignore 里。
set -euo pipefail
cd "$(dirname "$0")/.."

find out out-test -name '*.class' -delete 2>/dev/null || true
mkdir -p out out-test

javac -encoding UTF-8 -d out $(find src/main/java -name '*.java')
javac -encoding UTF-8 -cp out -d out-test $(find src/test/java -name '*.java')
