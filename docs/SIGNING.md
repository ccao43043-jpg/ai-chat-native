# 签名与覆盖安装

> **现行签名入口 · 2026-11-20 PST 对照 main `5e4502a` / v1.7.9。** 下列固定指纹与 Gradle/Release 记载一致；本次仅核对文档/源码，未读取私钥、密码或重验 APK。旧重写 SPEC 中 `6054…` 不是本仓库现行发布证书。

## 结论（2026-11-17 起）

正式给用户安装的 APK 必须用**固定证书**签名；仅在正确配置 `keystore.properties` 时，`debug` 与 `release` 才共用下列证书：

| 项 | 值 |
|---|---|
| 证书 DN | `CN=ai-chat-native, OU=Android, O=ai-chat, L=Beijing, ST=Beijing, C=CN` |
| 证书 SHA-256 | `A2:28:0D:C1:59:AC:F5:58:A5:AB:D0:19:A3:EF:A0:07:92:28:6D:6F:D7:FE:E0:71:C4:F5:EC:E2:E8:AC:2F:52` |
| keystore 路径 | `D:\android-dev\keystore\ai-chat-release.jks`（本机，**不进 Git**） |
| alias / 密码 | `aichat` / 见 `keystore.properties`（同样不进 Git） |
| 算法 / 有效期 | RSA 4096 / 10000 天（到 2053 年） |

Gradle 通过仓库根目录的 `keystore.properties` 读取（已被 `.gitignore` 忽略）：

```properties
storeFile=D:/android-dev/keystore/ai-chat-release.jks
storePassword=…
keyAlias=aichat
keyPassword=…
```

`app/build.gradle` 里 `signingConfigs.stable` 同时挂在 `buildTypes.debug` 与 `buildTypes.release` 上；没有 `keystore.properties` 时仍回退本机 debug 证书（CI 编译用），但那类产物**不能**覆盖安装到用户手机上。

## 为什么之前每次升级都要先卸载

Android 只允许同时满足以下两点的 APK 覆盖安装：

1. 新包与已装包的**签名证书完全相同**；
2. 新包的 `versionCode` **不小于**已装包。

在此之前，每次发版都由不同机器 / CI 临时环境构建，各自使用本机 `~/.android/debug.keystore`。结果是每次发的 APK 都是不同证书：

| 版本 | 证书 SHA-256 |
|---|---|
| v1.3.0 | `43bed124…cefe8` |
| v1.4.0 | `c3831930…a3a6` |
| v1.5.0 | `c40e3511…04778` |
| v1.5.1 起 | `a2280dc1…ac2f52`（固定） |

证书不同 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.aichat.app signatures do not match newer version`。**不要据此直接卸载**：先确认安装的是正确签名包；确需迁移时先完整备份并验证可恢复，卸载会清数据。

## 一次性迁移（只这一次）

历史不同证书版本不能直接覆盖到固定证书版。若仍安装这类旧版本，应先核对实际证书；**只有确认必须迁移且备份可恢复后**才进行下列历史迁移流程（当前 v1.5.1+ 同证书升级无需此流程）：

1. 应用内「设置 › 数据与备份 › 导出整库 zip」（Key 不在 zip 里，需重填）
2. 卸载旧版
3. 安装 `ai-chat-v1.5.1.apk`
4. 「设置 › 数据与备份 › 导入整库 zip」

**从 v1.5.1 往上的固定证书正式包**，证书一致且 versionCode 不降时不需要卸载。不能把 CI 临时证书包或旧 `ai-chat-app` 包也视为兼容。

## 以后必须遵守

- 发版包只能在**有 `keystore.properties` 的机器**上构建；别人拿到代码也不必知道私钥。
- `D:\android-dev\keystore\ai-chat-release.jks` 必须备份到仓库外（U 盘 / 网盘 / 密码管理器）。**丢失后所有已装设备都无法再覆盖升级**，只能再次卸载重装。
- 校验命令：

```powershell
D:\android-dev\sdk\build-tools\34.0.0\apksigner.bat verify --print-certs app-release.apk
# 应输出 Signer #1 certificate SHA-256 digest: a2280dc159acf558a5abd019a3efa00792286d6fd7fee071c4f5ece2e8ac2f52
```

- 仓库是公开的，因此 keystore 与密码**永远不要**提交进 Git；CI 做单测、debug/release 编译及独立模拟器测试；CI 上传的临时证书 APK 只供检查，不是给用户覆盖安装的正式包。
