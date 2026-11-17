#!/usr/bin/env bash
# CI 上在无头模拟器里跑 instrumented 测试。
#
# 为什么要单独成文件：reactivecircus/android-emulator-runner 会对 inline script 做处理
# （多行 `|| { ... }` 会被拼成一行），直接写在这里会报
#   /usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "}")
# 所以 workflow 里只留一行 `bash scripts/ci/instrumented.sh`。
#
# 失败时先把 logcat 与语义树落盘再重试：AppRootUiTest 的 TestWatcher 会在用例失败时
# 把 Compose 语义树打进 logcat（tag=UITEST），没有这份日志就只能靠本地复现猜原因。
# 不用 `set -e`：这里要靠退出码自己判断要不要重试。

set -uo pipefail

run_once() {
  ./gradlew --no-daemon --console=plain :app:connectedDebugAndroidTest
}

adb logcat -c || true

if run_once; then
  echo "instrumented 第一次就通过"
  exit 0
fi

echo "::warning::第一次 instrumented 失败，收集日志后重试一次"
adb logcat -d > logcat-first-failure.txt || true
adb logcat -d -s UITEST:V > uitest-tree.txt || true

# 重试前清掉上次失败的安装残留，避免 INSTALL_FAILED_ALREADY_EXISTS / 残留进程
adb uninstall com.aichat.app || true

if run_once; then
  echo "instrumented 重试通过"
  exit 0
fi

echo "::error::instrumented 两次都失败"
adb logcat -d > logcat-second-failure.txt || true
adb logcat -d -s UITEST:V > uitest-tree-second.txt || true
exit 1
