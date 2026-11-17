#!/usr/bin/env bash
# CI 上在无头模拟器里跑 instrumented 测试。
#
# 为什么要单独成文件：reactivecircus/android-emulator-runner 会对 inline script 做处理
# （多行 `|| { ... }` 会被拼成一行），直接写在这里会报
#   /usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "}")
# 所以 workflow 里只留一行 `bash scripts/ci/instrumented.sh`。
#
# 2026-11-17 第二次实测（run 44029807873）：脚本跑通了，但两次都卡在装 APK：
#   第一次 `Failure calling service package: Broken pipe (32)`
#   第二次 `'package install-create ...' returns error "Can't find service: package"`
# 也就是 boot_completed=1 之后 package manager 还没真正可用（system_server 首次扫描未完成）。
# 对策：跑测试前先等到 `pm path android` 成功，再留 15 秒缓冲。
#
# 失败时先把 logcat 与语义树落盘再重试：AppRootUiTest 的 TestWatcher 会在用例失败时
# 把 Compose 语义树打进 logcat（tag=UITEST），没有这份日志就只能靠本地复现猜原因。
# 不用 `set -e`：这里要靠退出码自己判断要不要重试。

set -uo pipefail

# 等 adb + boot_completed + package manager 三件事都就绪。
# 只等 boot_completed 是不够的：实测它会早于 PM 就绪 10~30 秒。
wait_for_pm() {
  adb wait-for-device
  local i
  for i in $(seq 1 90); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] \
       && adb shell pm path android >/dev/null 2>&1; then
      echo "package manager 就绪（约 $((i * 5)) 秒）；再等 15 秒让首次扫描收尾"
      sleep 15
      return 0
    fi
    sleep 5
  done
  echo "::error::等不到可用 package manager（7.5 分钟）"
  adb devices || true
  return 1
}

run_once() {
  ./gradlew --no-daemon --console=plain :app:connectedDebugAndroidTest
}

adb logcat -c || true

if ! wait_for_pm; then
  echo "::error::模拟器未就绪，不跑测试"
  exit 1
fi

if run_once; then
  echo "instrumented 第一次就通过"
  exit 0
fi

echo "::warning::第一次 instrumented 失败，收集日志后重试一次"
adb logcat -d > logcat-first-failure.txt || true
adb logcat -d -s UITEST:V > uitest-tree.txt || true

# 重试前：清残留、并再次确认 PM 还活着（装 APK 失败时 system_server 可能已经挂了）
adb uninstall com.aichat.app || true
if ! wait_for_pm; then
  echo "::error::重试前模拟器已经不可用"
  exit 1
fi

if run_once; then
  echo "instrumented 重试通过"
  exit 0
fi

echo "::error::instrumented 两次都失败"
adb logcat -d > logcat-second-failure.txt || true
adb logcat -d -s UITEST:V > uitest-tree-second.txt || true
exit 1
