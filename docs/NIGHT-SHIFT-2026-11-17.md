# 夜班作业计划（2026-11-17 23:00 → 2026-11-18 09:00）

> 交给执行本计划的 AI：你没有我们之前的对话上下文，本文是**自包含**的。
> 所有路径、命令、行号、验收标准都是实测过的，直接照做即可。
> 遇到与本文冲突的实际情况，**以代码为准，并在报告里写明差异**。

---

## 0. 一页速览

| 项 | 值 |
|---|---|
| 仓库 | `https://github.com/ccao43043-jpg/ai-chat-native`（私有） |
| 本地工作副本 | `D:\android-dev\src\ai-chat-native` |
| 技术栈 | Kotlin + Jetpack Compose（纯原生，无 WebView），Gradle 8.7 / AGP 8.5 / JDK 17 / compileSdk 34 / minSdk 24 |
| 当前 `main` | `62028d4`（`versionCode 125` / `versionName 1.5.2`） |
| 当前质量门 | **413 个单测全绿**、12 个 instrumented 全绿、CI（`android.yml`）绿 |
| 你今晚的任务 | 5 个批次（A→E），每批一个独立 PR，**不许发版** |
| 硬禁止 | 不改版本号、不打 tag、不建 Release、不 force push `main`、不加新依赖、不动 keystore |

**先读三份文档再动手**（都在 `docs/`）：
1. `docs/README.md` —— 文档地图与「状态只查两处」的规则
2. `docs/MASTER-PLAN.md` §2（优先级+状态列）与 §5（完成度）—— **唯一真相源**
3. `docs/ASSESS-2026-11-17.md` —— 本轮诊断（你要做的 A/B/C 都在这里）

---

## 1. 环境自检（23:00–23:20，必做）

```powershell
# 1) 确认仓库状态
cd D:\android-dev\src\ai-chat-native
git status --short          # 必须干净
git log --oneline -1        # 必须是 62028d4（或更新的 origin/main）

# 2) 构建环境（三行环境变量必须设，否则 gradle 找不到 JDK/SDK）
$env:JAVA_HOME="D:\android-dev\jdk\jdk-17.0.21+9"
$env:ANDROID_HOME=$env:ANDROID_SDK_ROOT="D:\android-dev\sdk"
$env:GRADLE_USER_HOME="D:\android-dev\gradle-home"

# 3) 基线：跑一次单测，确认起点是 413/0
& "D:\android-dev\gradle\gradle-8.7\bin\gradle.bat" -p "D:\android-dev\src\ai-chat-native" `
  --no-daemon --offline --console=plain :app:testDebugUnitTest *> D:\android-dev\out\night-baseline.log
# 看结果：
Select-String -Path "D:\android-dev\src\ai-chat-native\app\build\test-results\testDebugUnitTest\TEST-*.xml" `
  -Pattern '<testsuite ' | ForEach-Object { $_.Line } |
  ForEach-Object { if ($_ -match 'tests="(\d+)".*failures="(\d+)".*errors="(\d+)"') { [pscustomobject]@{t=$matches[1];f=$matches[2];e=$matches[3]} } } |
  Measure-Object -Property t,f,e -Sum
```

**基线必须是 413 tests / 0 failures / 0 errors。** 不是的话先停下写进报告，别开始改代码。

### 1.1 构建/测试命令速查（照抄）

```powershell
$g = "D:\android-dev\gradle\gradle-8.7\bin\gradle.bat"
$repo = "D:\android-dev\src\ai-chat-native"
& $g -p $repo --no-daemon --offline --console=plain :app:compileDebugKotlin      # ~20s
& $g -p $repo --no-daemon --offline --console=plain :app:testDebugUnitTest      # ~60s
& $g -p $repo --no-daemon --offline --console=plain :app:assembleDebug          # ~15s
& $g -p $repo --no-daemon --offline --console=plain :app:assembleRelease        # 批次 A 用
```

- **`--offline` 必须有**。不加会去联网解析依赖，本机代理会把 gradle 挂死（进程空闲、日志 0 字节，只能 `Stop-Process -Name java -Force`）。
- **必须给 `-p <repo>`**（或先 `cd` 到仓库再跑 `gradlew`）。在别处跑会报 `Directory '...' does not contain a Gradle build`。
- 读 Kotlin 编译错误：日志里形如 `gradle.bat : e: file:///D:/.../Foo.kt:123:45` + **下一行**才是错误消息。

### 1.2 模拟器（批次 A 冒烟、批次 D 验证要用）

```powershell
Start-Process "D:\android-dev\sdk\emulator\emulator.exe" -ArgumentList `
  "-avd","dsh34","-no-window","-no-audio","-no-boot-anim","-gpu","swiftshader_indirect","-no-snapshot","-port","5554"
$adb = "D:\android-dev\sdk\platform-tools\adb.exe"
& $adb wait-for-device
do { Start-Sleep 2; $b = & $adb shell getprop sys.boot_completed } while ($b.Trim() -ne "1")   # 约 25s
& $adb install -r "D:\android-dev\src\ai-chat-native\app\build\outputs\apk\debug\app-debug.apk"
& $adb shell am start -W -n com.aichat.app/.MainActivity
& $adb logcat -b crash -d      # 必须为空
```

**坑**：`:app:connectedDebugAndroidTest` 会把 APK 卸载、清空 store。跑完要重新 `install` + 重新在 UI 里添加服务商。

### 1.3 本地假模型服务（联调用，不需要真 Key）

`D:\android-dev\mock\server.mjs`（node，端口 8787，无依赖）：

```powershell
Start-Process "D:\deep seek harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe" `
  -ArgumentList "D:\android-dev\mock\server.mjs" -RedirectStandardOutput "D:\android-dev\mock\out.log"
```

App 里加服务商：`设置 → 服务商 → 添加 → 自定义（OpenAI 兼容）`，Base URL 填 `http://10.0.2.2:8787/v1`，模型 `mock-1`。

**必须走 UI 添加**：直接改 `files/store/settings.json` 不生效，`AuthInjector` 会抛「服务商未登记」（KeyVault 里没有登记条目）。

---

## 2. 代码推送方式（重要，本机 git 直连时通时断）

```powershell
# PAT 位置（不要打印到日志里！）
$pat = (Select-String -Path "C:\Users\Administrator\.dsh\storages\mcp_connector.json" `
  -Pattern 'ghp_[A-Za-z0-9]{36}' -AllMatches | Select-Object -First 1).Matches[0].Value
$url = "https://x-access-token:$pat@github.com/ccao43043-jpg/ai-chat-native.git"

# 拉：必须显式指定 refspec
git -C D:\android-dev\src\ai-chat-native -c credential.helper= -c http.sslBackend=openssl `
  fetch $url "refs/heads/main:refs/remotes/origin/main"

# 推：-c credential.helper= 是关键（否则 Windows GCM 会拦成 Invalid username or token）
git -C D:\android-dev\src\ai-chat-native -c credential.helper= -c http.sslBackend=openssl `
  push $url "HEAD:refs/heads/<你的分支名>"
```

**失败处理**：`Recv failure: Connection was reset` / `Failed to connect after 21000 ms` 是常态，重试最多 3 次；仍失败则改用 REST Git Data API（脚本模板在 `D:\android-dev\out\rest-push-docs.ps1`，改 msg/title/body/branch 即可，**写文件传参，别把中文塞进 here-string**）：

```
POST /git/blobs   {content, encoding:"utf-8"}   # 每个文件一次
POST /git/trees   {base_tree: <main 的 tree.sha>, tree:[{path, mode:"100644", type:"blob", sha}]}
POST /git/commits {message, tree, parents:[<main sha>]}
POST /git/refs    {ref:"refs/heads/<branch>", sha}
POST /pulls       {title, head, base:"main", body}
```

GitHub API 调不通时先确认 `Invoke-RestMethod https://api.github.com/zen -Headers @{Authorization="Bearer $pat"}` 有返回。

---

## 3. 时间表（含缓冲，严格执行）

| 时段 | 批次 | 产出 |
|---|---|---|
| 23:00–23:20 | 环境自检 + 基线 | 基线日志（413/0） |
| 23:20–01:00 | **A 发布工程** | PR-A（R8 + release 冒烟） |
| 01:00–01:15 | 缓冲 / 合并 PR-A | — |
| 01:15–02:45 | **B 数据路径补测** | PR-B（单测 +≥22） |
| 02:45–03:00 | 缓冲 / 合并 PR-B | — |
| 03:00–04:00 | **C 静默失败治理** | PR-C |
| 04:00–04:15 | 缓冲 / 合并 PR-C | — |
| 04:15–07:15 | **D 图片外置**（最大，最可能超时） | PR-D 或「已回滚 + 诊断」 |
| 07:15–07:30 | 缓冲 / 合并 PR-D | — |
| 07:30–08:30 | **E 卫生 + 文档回写** | PR-E |
| 08:30–09:00 | 总报告 | `docs/NIGHT-SHIFT-REPORT.md` |

**单批卡住上限 45 分钟**：超时就 `git checkout -- .` 回滚该批未提交改动 → 在报告里写「卡点 + 已试方案」→ 跳到下一批。**不要为了做完而硬推一个没验证的改动。**

---

## 4. 批次 A：发布工程（P0，约 1.5 小时）

### 现状（已核实）
- `app/build.gradle`：`release { minifyEnabled false }`；`app/proguard-rules.pro` **文件不存在**；`lint { checkReleaseBuilds false; abortOnError false }`。
- 后果：至今所有发布包事实上都是「未混淆的 debug 级产物」。debug APK 11.70 MB，内含 16 个 `classes*.dex`、未压缩合计 28.4 MB。
- 签名已固定：`keystore.properties`（不入库）→ `D:\android-dev\keystore\ai-chat-release.jks`，证书 SHA-256 `a2280dc159acf558a5abd019a3efa00792286d6fd7fee071c4f5ece2e8ac2f52`。**不要动它。**

### 要做的事
1. 新建 `app/proguard-rules.pro`，先只放最小保留集（**先别加一堆猜测性 keep**）：
```proguard
# 保留行号信息，便于线上崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization（本项目大量手写编解码，但 JsonObject/JsonPrimitive 走反射较少；
# 若出现序列化相关崩溃，再按崩溃栈补 keep）
-keepclassmembers class kotlinx.serialization.json.** { *; }

# Compose 运行时不需要额外 keep（AGP 8.x 自带 consumer rules）
```
2. `app/build.gradle` 的 `release` 块改成：
```kotlin
release {
    signingConfig hasKs ? signingConfigs.stable : signingConfigs.debug
    minifyEnabled true
    shrinkResources true
    proguardFiles getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
}
```
3. **不要**打开 `lint { abortOnError true }`（离线环境会去拉 lint 传递依赖而失败）。

### 验收（全过才算完成）
```powershell
& $g -p $repo --no-daemon --offline --console=plain :app:testDebugUnitTest :app:assembleRelease
# 1) 单测仍 413/0
# 2) release APK 存在：
$aapt = "D:\android-dev\sdk\build-tools\34.0.0\aapt2.exe"
& $aapt dump badging "$repo\app\build\outputs\apk\release\app-release.apk" | Select-String "package:|application-label:"
# 3) 签名仍是同一张证书：
& "D:\android-dev\sdk\build-tools\34.0.0\apksigner.bat" verify --print-certs "$repo\app\build\outputs\apk\release\app-release.apk" |
  Select-String "SHA-256"
#    必须含 a2280dc159acf558a5abd019a3efa00792286d6fd7fee071c4f5ece2e8ac2f52
# 4) 体积对比（目标：比 debug 的 11.70 MB 小 ≥20%，即 ≤9.4 MB）
(Get-Item "$repo\app\build\outputs\apk\release\app-release.apk").Length / 1MB
# 5) 冒烟：装模拟器，五步全过、无崩溃
#    冷启动 → 发一条消息（mock）→ 打开抽屉 → 进设置页 → 返回聊天
```

### 若 release 版崩溃
- 用 `app/build/outputs/mapping/release/mapping.txt` + `retrace` 定位缺失的 keep。
- 回退顺序：① 关 `shrinkResources` 只留 `minifyEnabled` → ② 关 `minifyEnabled`（等于恢复现状）→ ③ 在报告里写「R8 不可行 + 崩溃栈」。
- **不允许**为了让 release 能跑而删掉业务代码或关掉功能。

---

## 5. 批次 B：数据路径补测（P1，约 1.5 小时）

### 现状
`app/src/test/` 共 413 例，但**三个「用户数据安全」相关目录是 0 测试**：
- `app/src/main/java/com/aichat/app/data/backup/`（75 行）—— 整库导出/导入，**写错一次就是数据丢失**
- `app/src/main/java/com/aichat/app/data/secret/`（118 行）—— KeyVault（Keystore AES-256-GCM）
- `app/src/main/java/com/aichat/app/data/files/`（595 行）—— 附件文本截断（D7 的核心）

### 要做的事（按优先级）
1. **`BackupService` 往返**（最高优先）：
   - 造一组合成数据（2 个对话、各 3 条消息、1 个世界书、1 条记忆、非默认 settings）→ `export` 到临时 zip → 清空 store → `import` → 断言逐字段相等（用已有的 `semanticEqual` 语义，不要比 JSON 字节）。
   - 负例三则：① 缺 `manifest.json` 的坏 zip → 抛可读异常，**store 不被破坏**；② 版本号不兼容 → 明确报错；③ 导入中途失败 → 原数据仍可用（先备份再替换）。
2. **`KeyVault`**：单测里 Keystore 不可用，先看 `data/secret/` 是否已有可注入的 `Vault` 接口/fake；没有就**只测纯逻辑**（键名规则、`registration` 缺失时的 `Rejected("服务商未登记")` 分支、迁移时的合并），**不要为了测试去改生产接口签名**。
3. **`FileText`**：`capText(text, 0/max/max-1/超长/纯中文/含 emoji)` 六个边界；`buildFileContext` 多文件时的总预算与顺序；`truncated/total` 字段正确性。

### 验收
- `:app:testDebugUnitTest` → **≥435 tests / 0 failures**（新增 ≥22 例）。
- 新增测试文件放在与被测类同包下，命名 `app/src/test/java/com/aichat/app/data/backup/BackupServiceTest.kt` 等。

---

## 6. 批次 C：静默失败治理（P1，约 1 小时）

### 现状（8 处空 catch，已逐个定位）
| 文件:行 | 判断 |
|---|---|
| `data/convlog/JsConvLog.kt:714` | 读旧 oplog 容错，可保留但需日志 |
| `data/convlog/JsConvLog.kt:825` | 同上 |
| `data/files/PdfText.kt:109` | PDF 解析失败 → 应回退纯文本 + 上报 |
| `data/store/StoreJs.kt:30` | **影响控制流**，必须显式分支 |
| `data/store/StoreJs.kt:50` | 设计上合理（探测文件是否存在），加注释即可 |
| `plugins/affect/Affect.kt:163` | **最值得修**：RP 判定吞异常 → 可能静默走错分支 |
| `ui/common/CharCard.kt:133` | UI 渲染容错，需日志 |
| `ui/settings/ProvidersScreen.kt:195` | UI 容错，需日志 |

### 要做的事
1. 逐处判断「读路径容错」还是「影响控制流」：
   - 读路径容错 → `catch (e: Throwable) { Log.w("AiChat", "<上下文>", e) }` + 一行注释写明「为什么可以吞」。
   - 影响控制流 → 显式返回/抛出，或走既有的错误上报通道（插件错误面板 `PluginRegistryImpl.pushErr`，UI 用 `controller.toast(...)`）。
2. `Affect.kt:163` 必须有测试：构造一个会让 `GCache.isRpConv` 抛异常的场景（用 fake），断言降级行为明确（不要静默返回错值）。
3. 目标：**空 catch 从 8 处降到 0**（全部带日志或显式处理）。

### 验收
```powershell
# 自查：应该只剩带日志的 catch
Select-String -Path "$repo\app\src\main\java\com\aichat\app\**\*.kt" -Pattern 'catch\s*\([^)]*\)\s*\{\s*\}'
# 期望 0 命中
```
- 单测全绿；新增 ≥2 例。

---

## 7. 批次 D：图片外置（P1，约 3 小时，最可能超时）

### 现状
`Message.images: List<String>`（`domain/model/Models.kt:119`）存的是 `data:image/jpeg;base64,...`（`ui/image/RefImageLoader.kt:43`，`MAX_EDGE=1024`、`MAX_BYTES=4MB`）。一轮 10 张参考图 ≈ **1.4–2.7 MB** 直接写进 `conv_*.log.jsonl`。

### 要做的事（**渐进，先做只读兼容**）
1. **第一步（必须完成，1 小时）**：解析侧支持 `img:<id>` 前缀 —— 新增 `data/files/ImageStore.kt`（`put(bytes): String` 返回 `img:<sha8>`、`get(id): ByteArray?`、路径 `files/images/<id>.jpg`），并在 `ui/common/RemoteImage.kt`（`ImageCache.load`）里加一条分支：`src.startsWith("img:") -> ImageStore.get(...)` → `BitmapFactory.decodeByteArray`。
2. **第二步（1 小时）**：写入侧改为落盘 + 存引用 —— `RefImageLoader.toDataUrl` 之外新增 `toImageRef`，发送带图消息时写文件存 `img:<id>`。**保留**旧 `data:` 路径的读取能力（旧对话不能挂）。
3. **第三步（1 小时，可选）**：惰性迁移 —— 打开旧对话时把 `data:` 转存成文件并改写 oplog（`JsConvLog` 里加一个 `migrateImages(convId)`，带开关 `files.imageStore`，默认 true，失败不影响打开）。

### 验收
- 新发一条带 3 张图的消息后：`run-as com.aichat.app ls -l files/images/` 有 3 个文件；**该对话的 `.log.jsonl` 增长 < 5 KB**。
- 旧对话（含 base64 图）仍能正常显示图片。
- 导出 zip 包含 `images/` 目录，导入后图片可见。
- 单测全绿 + 新增 ≥8 例（`ImageStore` put/get/去重/缺失、`ImageCache` 双前缀分支）。
- **做不到第三步就只交前两步**，在报告里写清剩余。

---

## 8. 批次 E：卫生 + 文档回写（约 1 小时）

1. **`.github/workflows/diag.yml`**：一次性排障遗留、带 `contents: write`、会往 `ci-logs` 分支推文件。改为 `on: workflow_dispatch` only 且去掉 `contents: write`；或直接删（在 PR 里说明）。
2. **根目录 `tests/`**（0.95 MB 旧 JS 测试，CI 不跑）：加一个 `tests/README.md` 说明「旧网页版遗产，不参与构建，仅作行为参照」，**不要删**（它们是 v0.28.7 的行为基准）。
3. **文档回写**（必做，否则又会出现「做完了没回写」）：
   - `docs/MASTER-PLAN.md`：§2 里把批次 A/B/C/D 对应行（P0「发布工程」、P1.5 #14/#15/#16/#17）的状态改成 `✅（PR #xx）`；§5.1 完成度对应数字更新；文首「最后核实」改成 `main @ <你合并后的 sha>` + 时间。
   - `docs/ASSESS-2026-11-17.md` §7 变更记录追加一行。
   - 若引入新开关（`files.imageStore`），在 `docs/NATIVE_REWRITE_SPEC.md` §3.5 偏离登记表加一行。

---

## 9. 提交与 PR 规范

- 分支名：`night/a-release-r8`、`night/b-data-tests`、`night/c-silent-fail`、`night/d-image-store`、`night/e-hygiene`。
- **每批一个 PR，基于最新的 `origin/main`**（开工前先 `git fetch` 并 `git rebase origin/main`，否则 PR 会 `dirty`）。
- commit message：`<type>(<scope>): <一句话>` + 空行 + 「为什么」+「验证」。type 用 `feat/fix/test/chore/docs`。
- 提 PR → **等 CI `build` success** → `PUT /pulls/<n>/merge {merge_method:"squash"}` → 合并后 `git checkout main && git reset --hard origin/main` 再开下一批。
- 中文提交信息写文件再 `git commit -F`，文件用**无 BOM 的 UTF-8**（`New-Object Text.UTF8Encoding($false)`）。带 BOM 会出现在提交信息里。

---

## 10. 硬禁止（违反任何一条都算失败）

1. **不改 `versionCode`/`versionName`**（保持 `125` / `1.5.2`）。
2. **不打 tag、不建 Release、不发布**。发版必须由人类明确指示。
3. **不 force push `main`**；不删任何 Release / tag / 分支。
4. **不动** `D:\android-dev\keystore\ai-chat-release.jks`、`keystore.properties`、`.gitignore`。
5. **不加新依赖**（离线环境装了也拉不到，且会污染依赖面）。
6. **不做大规模重命名/搬包**（今晚不是重构窗口）。
7. **不为了让测试变绿而放宽断言**（尤其 golden：`app/src/test/resources/**` 是字节级契约，改之前想清楚）。
8. **不动 `app/src/main/assets/www/`**（旧网页版的 golden 参照，4 MB 不进 APK）。

---

## 11. 每批交付的报告格式

每个 PR 合并后在 `docs/NIGHT-SHIFT-REPORT.md` 追加一节：

```markdown
## 批次 A · 发布工程（PR #100，合并 commit abc1234）
- 改了什么：<3 行以内>
- 证据：单测 413/0 → 413/0；APK 11.70 MB → x.xx MB；证书 a2280dc1… 未变；冒烟五步全过
- 卡过什么：<1 行，没有就写「无」>
- 遗留：<1 行，没有就写「无」>
```

**09:00 前交总结**：`docs/NIGHT-SHIFT-REPORT.md` 顶部加一段「总览」——完成批次 / 未完成批次 + 原因 / `main` 最终 sha / 单测数 / 需要人类决策的 3 件事（最多 3 条）。

---

## 12. 已知坑位清单（省你时间）

| 坑 | 症状 | 解法 |
|---|---|---|
| 没加 `--offline` | gradle 卡死、日志 0 字节 | 加 `--offline`；清理 `Stop-Process -Name java -Force` |
| `.ps1` 存成无 BOM 的中文 | 中文锚点变乱码、`Replace` 全 MISS | 写脚本用 `UTF8Encoding($true)`（带 BOM） |
| `[IO.File]::ReadAllText("相对路径")` | 找不到文件（按 `D:\deep seek harness` 解析） | 一律绝对路径 |
| `Get-Content` 读源码 | 中文注释乱码 | `[IO.File]::ReadAllText($f,[Text.Encoding]::UTF8)` |
| 仓库 `core.autocrlf=true` | 改一个文件显示几百行 diff | 编辑时先统一 LF；提交信息注明「忽略行尾后 X 增 Y 删」 |
| `connectedDebugAndroidTest` | 跑完 APK 被卸载、store 清空 | 跑完重新 `install` + UI 里重新加服务商 |
| 直接改 `settings.json` 加服务商 | 发送报「服务商未登记」 | 必须走 UI 添加（要写 Keystore 登记） |
| `git push` 报 Invalid username | GCM 干预 | `-c credential.helper=` |
| PR 显示 `dirty`、`changed_files` 异常大 | base 落后于 `main` | `git fetch` + `git rebase origin/main` 后 force push |
| Compose UI 测试 flake | `ComposeTimeoutException` | 先看是不是 `Finished 0 tests`（环境没起来）；用 `waitUntil` 而非 `assertIsDisplayed`；LazyColumn 内用 `performScrollToNode` 不是 `performScrollTo` |

---

## 13. 完成定义（DoD）

今晚算成功，需要同时满足：

- [ ] 至少 3 个批次合并进 `main`（A、B 为必达；C/D/E 尽力）
- [ ] `main` 上 `:app:testDebugUnitTest` 全绿，测试数 ≥435
- [ ] `main` 上 CI（`android.yml`）绿
- [ ] 版本号仍是 `125` / `1.5.2`，无新 tag、无新 Release
- [ ] `docs/MASTER-PLAN.md` 的状态列与本轮实际一致（这是最容易忘的一条）
- [ ] `docs/NIGHT-SHIFT-REPORT.md` 有总览 + 每批一节
