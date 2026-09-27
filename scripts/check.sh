#!/usr/bin/env bash
# 固定验收程序入口：先编译，再跑 check/Checker.java。
# 参数（-list / --only <组>）原样透传给 Checker。
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/build.sh

find out-check -name '*.class' -delete 2>/dev/null || true
mkdir -p out-check
javac -encoding UTF-8 -cp out -d out-check check/Checker.java

# Windows 的 classpath 分隔符是 ';'，类 Unix 是 ':'。
CPSEP=':'
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
esac

exec java -Dfile.encoding=UTF-8 -cp "out${CPSEP}out-check" Checker "$@"
