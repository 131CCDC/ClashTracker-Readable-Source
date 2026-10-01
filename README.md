# ClashTracker 与 ClashTracker Root Installer（源码快照）

> English summary at the bottom.

## 这是什么

本仓库是 **ClashTracker（Android 悬浮窗版）** 与 **ClashTracker Root Installer
（机内 root 安装器）** 两个 Android 应用的**可读源码快照**，另附理解与构建安装器
原生探针所需的 **probe 源码**（仅源码、构建脚本与许可/声明文档）。

**重要说明（请先阅读）：**

- 这是经过人工整理的**可读源码快照**，**不是** APK 反编译/反汇编产物，也**不声称**
  是完整或原始的上游源码。
- **不保证可以直接构建成功**：缺少卡牌资产与本地生成的原生产物（见下文），需要按
  说明自行补齐。
- 本仓库不包含 APK、游戏资源、卡牌美术、抓包数据、编译后的原生探针产物，或任何
  账号/设备数据。

## 快照版本

| 应用 | versionCode | versionName |
| --- | --- | --- |
| ClashTracker（`:app`） | 10 | `2.8-history-reconcile` |
| ClashTracker Root Installer（`:installer`） | 3 | `2.1-tablet` |

工具链：Android Gradle Plugin 8.5.2、Kotlin 2.0.21、Gradle Wrapper 8.9、
`compileSdk`/`targetSdk` 34、`minSdk` 26、Java 17。

> 用于对照的平板 APK 是更早的构建：ClashTracker `2.3-tablet`（versionCode 5）、
> Installer `2.0-tablet`（versionCode 2）。本快照源码比这两个 APK 更新。

## 被有意省略的内容

为避免版权与隐私问题，以下内容**未包含**：

- 任何 APK（`*.apk`）；
- 卡牌美术与资源（`assets/cards/` 下的 `cards.json`、PNG 与 `faces/`）——再分发
  状态不明确；
- 抓取到的对战回放 / 账号 / 设备数据（例如被省略的测试夹具，以及
  `research/battlelog/raw/`、`cmd_audit/`、UI dump、截图、日志）；
- 编译后的原生探针产物（`libscid_sdk.so` 等）；
- 本地设置（`settings.local.json`、`local.properties`）与本地导入目录
  （`tablet-import/`，已在 `.gitignore` 中忽略）。

## 构建限制

- **`:app`** 通过 `sourceSets["main"].assets.srcDir(rootProject.file("../assets/cards"))`
  引用 `../assets/cards`。该目录在本快照中被省略，因此卡牌名称、费用与美术可能
  不完整，直到使用者自行放入**合法获得**的兼容资源（参见 `assets/cards/README.md`）。
- **`:installer`** 的 `stageProbe` 任务期望
  `probe/artifacts/candidates/stable-candidate/libscid_sdk.so`。该 `.so` 是
  `probe/build_probe.ps1` 的构建产物，需要本地使用 Android NDK（脚本以 r27c 测试）
  自行编译；本仓库只提供源码。
- 因此，本快照**不保证**开箱即可 `assembleDebug`。

## 目录结构

```text
.
├── LICENSE                      # Apache-2.0
├── NOTICE                       # 本快照的声明（改编自源仓库 NOTICE）
├── README.md                    # 本文件
├── .gitignore
├── assets/
│   └── cards/
│       └── README.md            # 说明卡牌资源被有意省略
├── android/                     # Android Gradle 工程
│   ├── build.gradle.kts         # AGP 8.5.2 / Kotlin 2.0.21
│   ├── settings.gradle.kts      # :app + :installer
│   ├── gradle/wrapper/          # Gradle 8.9 wrapper
│   ├── README.md                # 应用详细文档（已做隐私替换）
│   ├── app/                     # ClashTracker（vc10 / 2.8-history-reconcile）
│   └── installer/               # Root Installer（vc3 / 2.1-tablet）
└── probe/                       # 原生探针源码、构建脚本、许可/声明
    ├── nulls_probe.cpp
    ├── *.inc / *.h / *_arm64.S  # 探针源片段
    ├── build_probe.ps1          # 本地 NDK 构建脚本
    ├── FIRSTLIGHT_LICENSE
    ├── FIRSTLIGHT_NOTICE.md
    └── UPSTREAM_NOTICE
```

## 许可与声明

- 根目录 `LICENSE` 为 Apache-2.0。
- `NOTICE` 说明本快照的分发边界。
- `probe/` 中 FirstLight CR 相关部分见 `probe/FIRSTLIGHT_LICENSE`、
  `probe/FIRSTLIGHT_NOTICE.md` 与 `probe/UPSTREAM_NOTICE`。
- 本项目与 Supercell / Null's / MuMu 无隶属关系；产品名称仅用于说明兼容性。

## 隐私处理

快照中的文本已做隐私替换：真实设备序列号与账号 ID 被替换为醒目的合成示例，
含 `C:\Users\...` / `D:\...` 的绝对本地路径已移除。除这些仅涉及隐私的替换外，
源码逻辑保持不变。

## English summary

A curated, readable **source snapshot** of the two Android apps — ClashTracker
(`:app`) and ClashTracker Root Installer (`:installer`) — plus the source-only
`probe/` needed to understand and build the installer's native payload.

This is **not** APK decompilation output and **not** a claim of complete or
original upstream source, and it is **not guaranteed to build as-is**: the app
expects `../assets/cards`, and the installer's `stageProbe` expects a locally
built `probe/artifacts/candidates/stable-candidate/libscid_sdk.so`. Snapshot
versions: app versionCode 10 / `2.8-history-reconcile`, installer versionCode 3
/ `2.1-tablet` (the older tablet APKs inspected were app `2.3-tablet` vc5 and
installer `2.0-tablet` vc2). Omitted: APKs, card art/resources with unclear
redistribution status, captured replay/account/device data, and compiled native
probe artifacts. Licensed under Apache-2.0 — see `LICENSE`, `NOTICE` and the
`probe/` notice files.
