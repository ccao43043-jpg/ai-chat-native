# 打包与发版

## 两条产物，别混用

| 用途 | 命令 | 产物 | 特点 |
| --- | --- | --- | --- |
| **给别人装**（Release 附件） | `./gradlew :app:assembleRelease` | `app-release.apk` | 过 R8：混淆 + 资源裁剪，**2.3 MB / 1 个 dex** |
| 自己调试、跑 instrumented | `./gradlew :app:assembleDebug` | `app-debug.apk` | 不混淆，11.7 MB / 16 个 dex，可 `run-as` 看数据 |

两者用**同一张证书**（`keystore.properties` → `signingConfigs.stable`，见 `SIGNING.md`），所以可以互相覆盖安装，数据不丢。

## 为什么 release 包小这么多

`minifyEnabled true` + `shrinkResources true` + `proguard-android-optimize.txt`：

- **代码**：R8 按可达性裁剪 + 改名，`classes*.dex` 从 28.4 MB（未压缩）降到 3.6 MB，dex 数量 16 → 1。
- **资源**：`shrinkResources` 删掉未被引用的 drawable/layout；`assets.srcDirs = []`（见 `app/build.gradle`）让旧版 `www/` 的 4 MB 压根不进包。
- 冷启动实测：debug 3523 ms → release 1177 ms（模拟器 dsh34，同一份 store）。

规则写在 `app/proguard-rules.pro`，只保留两类东西：

1. **kotlinx.serialization 的编译器生成物**（`$$serializer` / `Companion.serializer()`）—— R8 看不到调用点，必须整类保留；
2. **枚举的 `values()/valueOf()` 与字段** —— 我们把枚举名写进了 JSON（`Role`、`ProviderType`、`Slot`、`MissReason`），裁掉就会在解析旧数据时炸。

> 本项目**不用反射解析模型**：`Message` / `Conversation` 全部手写 codec，直接读 `JsonElement`。这是 R8 能开得这么激进的前提。**以后若引入 `@Serializable` 数据类或反射式序列化，先补规则再合并。**

## 发版流程

```bash
# 0) 版本号（app/build.gradle）：versionCode 与 versionName 一起改
# 1) 全量测试
./gradlew --no-daemon --offline --console=plain :app:testDebugUnitTest
# 2) 打包（release！）
./gradlew --no-daemon --offline --console=plain :app:assembleRelease
# 3) 校验
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -3
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# 4) 装机 smoke：覆盖安装 → 冷启动 → 发一条消息 → 重启后消息还在
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n com.aichat.app/.MainActivity
# 5) tag + Release（附 app-release.apk 改名成 ai-chat-native-<版本>.apk）
```

证书指纹必须是 `A2:28:0D:C1:…:2F:52`（见 `SIGNING.md`）；不对就停，别发。

## 已知取舍

- release 包**不可 `run-as`**，出问题时看不了 `files/store/`。排查阶段装 debug 包，两者可互换覆盖安装。
- CI 每次都会编一遍 release（`.github/workflows/android.yml`）：R8 把序列化器/枚举裁掉的错只会在运行时炸，编一次是唯一能提前发现的办法。
- 没有 `network_security_config`：用户自填 http 网关（局域网 Ollama、本地 mock）必须允许明文，见 `AndroidManifest.xml` 的 `usesCleartextTraffic`。