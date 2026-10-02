# Ice 防护（IceGuard）

[![Android CI](https://github.com/ice-wocker/IceGuard/actions/workflows/build.yml/badge.svg)](https://github.com/ice-wocker/IceGuard/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ice-wocker/IceGuard?include_prereleases&label=release&color=2ea44f)](https://github.com/ice-wocker/IceGuard/releases)
[![License](https://img.shields.io/github/license/ice-wocker/IceGuard?color=blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![minSdk](https://img.shields.io/badge/minSdk-26-blue)](#)
[![No Internet](https://img.shields.io/badge/permission-%E6%97%A0%20INTERNET-critical)](#%E6%9D%83%E9%99%90%E8%AF%B4%E6%98%8E)

> 一个**完全本地运行**的 Android 安全体检工具。不联网、不上传、无追踪。

Ice 防护通过审计已安装应用的权限组合、检测设备环境风险、静态分析本地安装包，
给出一个可解释的安全评分。所有分析都在设备本地完成——**应用本身不申请网络权限**，
这一点可以在编译产物中验证。

## 为什么做这个

市面上大量"手机安全卫士"类应用存在三个问题：过度索取权限（尤其是无障碍服务）、
无法验证的行为、以及夸大宣传（如"实时云查杀""病毒库比对"）。

Ice 防护的设计原则相反：

- **能力边界明确**——只做技术上确实能做到的事，不做无法验证的承诺
- **零网络请求**——不申请 `INTERNET` 权限，数据不可能外传
- **判定依据可解释**——每个评分都能追溯到具体规则，不给出黑箱结论
- **代码完全开源**——可自行审计每一条判定逻辑

## 功能

| 模块 | 能力 | 实现方式 |
|---|---|---|
| **应用权限审计** | 枚举已安装应用，评估权限组合风险 | `PackageManager` + 自研组合规则引擎 |
| **安装包静态扫描** | 解析本地 APK 的清单，评估其权限 | 自研 AXML 二进制解析器 |
| **设备环境检测** | Root 痕迹、危险设置、无障碍服务、设备管理器 | 多策略交叉探测 + 系统设置读取 |
| **安全体检** | 一键串联全部分析，生成百分制评分 | 加权汇总模型 |
| **报告导出** | Markdown / JSON 格式 | 自研序列化 |
| **安全守护（四条检测通道）** | 安装 / 更新、开机自检、前台唤醒、周期巡检，事件一到即判定并处置 | 广播 + WorkManager + UsageStats/无障碍事件 |
| **秒级终止（可选授权）** | 亚秒级 `am force-stop` 杀进程，按策略追加 `pm disable-user` 冻结 | Shizuku（ADB 级权限）`am` / `pm` 命令 |
| **应用处置（可选授权）** | 终止 / 停用 / 卸载 / 清除数据 / 撤销权限已标记的应用 | Shizuku（ADB 级权限）`pm` 命令 |
| **组件级清单分析** | 解析 activity/service/receiver/provider 与 `exported`，识别暴露面 | 自研 AXML 解析器 + PackageManager |
| **安装来源与签名检测** | 侧载识别、调试标志、证书 SHA-256（供自行核对） | PackageManager + InstallSourceInfo |
| **拦截日志** | 每次判定与处置的时间、通道、命令返回全部留痕 | 本地 SharedPreferences |

### 「1 秒终止」到底能做到什么程度

这是最容易被夸大的一句话，所以这里把边界写清楚：

- ✅ **终止是真的**：经 Shizuku 授权后，`am force-stop` 会在**亚秒级真正结束目标进程**，
  且不像 `kill` 那样被系统立刻拉起；再追加 `pm disable-user` 可让它彻底起不来。
  日志里会记录实际耗时与命令返回，可在设备上自行核验。
- ❌ **"监控到病毒发作"做不到**：Android 沙箱隔离了应用间观测，第三方应用看不到别的应用的
  行为、内存与日志。因此不存在"实时行为监控"这种能力。
- ⚠️ **所以本项目给的是「事件驱动的秒级响应」**：在**可观测事件**（安装、更新、开机、
  应用被切到前台、周期巡检）发生时，立即完成判定与处置。瓶颈在**检测**，不在**终止**。

任何宣称能在非 Root 手机上"实时监控病毒行为并 1 秒击杀"的产品，要么依赖 Root，
要么在夸大其词——本项目的取舍是把能做的做扎实，做不了的如实说。

### 明确不做的功能

以下能力在非 Root 的现代 Android 上第三方应用**无法实现**，因此本项目不提供：

- ❌ 实时**行为**监控（看不到别的应用在做什么；上面已说明本项目改用事件驱动）
- ❌ 云端病毒库比对（本项目不联网，也不收集样本）
- ❌ 用无障碍服务**模拟点击**"清理"（这正是许多安全应用滥用的高危权限；
  本项目的无障碍通道只读包名，不具备也不使用任何操作能力）
- ❌ 安装前阻止安装（系统没有给第三方应用这个 API）

**关于"处置"的诚实边界**：授权 Shizuku 后拿到的是 shell（ADB，uid 2000）级权限，
能做的仍是包管理层面的动作——`am force-stop` 终止、`pm disable-user` 停用、
`pm uninstall` 卸载、`pm clear` 清除数据、`pm revoke` 撤销权限。
**读不到其他应用的内存，也看不到它的行为**。这不是"实时拦截"，
但它是非 Root 手机上真能生效的最强手段。

## 核心设计

### 权限组合评分模型

单个高危权限判别力有限——绝大多数正常应用都需要存储权限。
真正有判别力的是**权限组合**。

Ice 防护内置 18 条组合规则，例如：

| 组合 | 追加权重 | 判定理由 |
|---|---|---|
| 读取短信 + 联网 | +45 | 具备静默窃取验证码短信的技术条件 |
| 接收短信 + 联网 + 无障碍 | +60 | 可自动读取验证码并模拟点击，典型盗刷木马组合 |
| 安装应用 + 悬浮窗 | +45 | 可弹覆盖层诱导点击并静默安装（全家桶手法） |
| 设备管理器 + 悬浮窗 | +45 | 难以卸载且可遮挡界面，具备锁机勒索特征 |
| 后台定位 + 联网 | +35 | 可在无感知状态下持续上报位置轨迹 |

算法细节见 [`PermissionRules.kt`](app/src/main/java/com/ice/guard/core/rules/PermissionRules.kt)：
取「单权限最高分 + 组合加成 × 0.6」，而非简单求和——
避免权限数量多的正常应用被误判为高危。

### 自研 AXML 解析器

APK 内的 `AndroidManifest.xml` 是编译后的二进制格式，无法当作文本读取。
本项目**没有引入 apk-parser 等第三方库**，而是按 AOSP 定义的 chunk 格式
手写了解析器（[`AxmlParser.kt`](app/src/main/java/com/ice/guard/core/scanner/AxmlParser.kt)）。

实现要点：

- 字符串池需按 `flags & 0x100` 区分 **UTF-8 与 UTF-16** 两种编码路径
  （实测 AAPT2 输出为 UTF-16，不能假定 UTF-8）
- 属性区偏移：`attributeStart` 与 `attributeCount` 位于 node 起始的 `+8` / `+12`
- 所有越界访问均有防护，单个属性解析失败不影响整体
- 解析异常统一转为 `success=false` 返回值，不向调用方抛异常

解析器正确性由**真实样本**验证：测试夹具 `real_manifest.axml` 直接从本工程的
debug APK 中取出的、经 AAPT2 编译与 manifest merger 合并后的二进制清单
（17940 字节，含 25+ 个组件），期望值全部取自 `aapt2 dump xmltree` 输出：

```bash
unzip -o app-debug.apk AndroidManifest.xml -d /tmp        # 即为夹具本身
aapt2 dump xmltree --file AndroidManifest.xml app-debug.apk   # 权威对照
```

### 安全守护：四条检测通道 + 一条统一响应管线

```
通道 1  安装 / 更新        PACKAGE_ADDED · PACKAGE_REPLACED        （零特殊权限）
通道 2  开机自检           BOOT_COMPLETED                         （零特殊权限）
通道 3  前台唤醒           前台监听（需「使用情况访问」授权）         （默认关闭）
通道 4  无障碍前台事件     只读包名，不读内容、不模拟操作            （默认关闭）
        ─────────────────────────────────────────────────────────
        周期巡检兜底        WorkManager，最小间隔 15 分钟
                │
                ▼
        AutoResponder：取分 → 读策略 → 决策 →（可选）终止 → 落日志 → 通知
```

**决策优先级**（实现见 [`AutoResponder.kt`](app/src/main/java/com/ice/guard/core/guard/AutoResponder.kt)）：

1. 评分 < 阈值 → 忽略，**不写日志**，避免把日志变成流水账；
2. 评分 ≥ 阈值，但未开自动处置 / 未授权 Shizuku → **只告警**（默认行为）；
3. 评分 ≥ 阈值且允许自动处置 → `am force-stop`，按策略追加 `pm disable-user`。

三个容易被忽略的工程细节：

- **默认绝不自动动手**：`autoRespond` 默认 `false`，默认状态下本应用只提醒、不处置。
- **前台通道带 10 分钟体检缓存 + 30 秒处置节流**：否则每次切换应用都要查一遍
  PackageManager，会拖慢整机。
- **状态漂移检测**：开机巡检会复查"曾被冻结的应用是否又活了"——单次处置不是终点。

## 权限说明

本应用声明以下权限，每一项都有明确的单一用途：

| 权限 | 用途 | 必要性 |
|---|---|---|
| `QUERY_ALL_PACKAGES` | Android 11+ 枚举已安装应用 | 权限审计功能的前提 |
| `READ_EXTERNAL_STORAGE`（≤ Android 12） | 读取存储中的 APK 文件 | 安装包扫描功能 |
| `POST_NOTIFICATIONS`（Android 13+） | 发现高风险应用、或已执行终止时发本地提醒 | 守护告警 |
| `RECEIVE_BOOT_COMPLETED` | 开机后重排巡检任务并跑一次开机自检 | 守护通道 2 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 守护模式的常驻通知（Android 14 起前台服务必须声明类型） | 守护模式（默认关闭） |
| `PACKAGE_USAGE_STATS` | 前台唤醒监听（需**用户在系统设置中手动授予**，应用无法自行申请） | 守护通道 3（默认关闭） |

处置功能**不通过声明系统权限实现**，而是经用户显式授权后借用 Shizuku 的 shell 权限，
可随时在 Shizuku 应用中撤销。

守护模式、自动处置、前台监听**均为默认关闭**，由用户在「守护中心」自行开启。

### 无障碍通道的自我限制

守护通道 4 使用无障碍服务，但只用了它能力的**最小一片**：

| 能力 | 本项目 |
|---|---|
| 读取屏幕内容 | ❌ `canRetrieveWindowContent=false` |
| 模拟点击 / 手势 | ❌ 未声明 `canPerformGestures`，代码中不存在 `dispatchGesture` / `performAction` |
| 读取输入文本 | ❌ 只监听 `typeWindowStateChanged`，不监听文本事件 |
| 获知"哪个应用被打开" | ✅ 仅此一项，用于触发风险判定 |

配置见 [`accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml)，
代码见 [`GuardAccessibilityService.kt`](app/src/main/java/com/ice/guard/core/guard/GuardAccessibilityService.kt)。

**不申请 `INTERNET` 权限**——这意味着应用在技术上不具备任何联网能力，
不存在数据外传的通道。可通过 `aapt2 dump permissions` 自行验证编译产物。

## 权限处置与 Shizuku（可选）

Ice 防护的"处置"能力是**可选的**：不装 Shizuku 时，本应用就是纯粹的体检工具，
所有功能照常。只有需要"停用/卸载高危应用"时，才需要走下面这条授权流程。

### 为什么必须用户手动配对

Shizuku 要以 shell 身份启动一个服务进程，而"以 shell 身份启动"这一步必须由用户完成：
要么在电脑上跑一次 `adb shell sh .../start.sh`，要么用 Android 11+ 的**无线调试配对**。

**这一步没法由 App 代劳**——如果把 Shizuku 的代码打进本 APK，它启动时依然是普通应用
的 uid，起不来服务。集成度再高也不能凭空获得 shell 权限。这是权限模型的硬约束，
不是实现选择。

### 无线调试配对步骤

1. 安装 [Shizuku](https://github.com/RikkaApps/Shizuku) 应用
2. 「设置 → 关于手机」连点「版本号」7 次，打开开发者选项
3. 「开发者选项 → 无线调试」开启，点「使用配对码配对设备」记下配对码
4. 在 Shizuku 应用内选择「通过无线调试启动」，输入配对码完成配对
5. 回到 Ice 防护的「权限与处置」页，点「请求授权」

### 授权后能做什么

| 操作 | 命令 | 效果 |
|---|---|---|
| 停用 | `pm disable-user --user 0 <pkg>` | 冻结应用，图标消失、无法运行，可恢复 |
| 卸载 | `pm uninstall --user 0 <pkg>` | 卸载（仅当前用户） |
| 清除数据 | `pm clear <pkg>` | 抹除应用数据，保留本体 |
| 撤销权限 | `pm revoke <pkg> <perm>` | 收回指定运行时权限 |

**做不到**：查看/终止其他应用的进程、读取其内存、实时行为监控。

### 许可证与合规

本应用只通过 Maven 依赖引入 Shizuku 官方的 `api` 与 `provider` 制品（均 Apache-2.0），
**不内嵌、不重打包 Shizuku 应用本体**。详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
与 [NOTICE](NOTICE)。

## 更新日志

版本改动见 [`CHANGELOG.md`](CHANGELOG.md)。

## 构建

### 环境要求

- JDK 17
- Android SDK（compileSdk 34）
- Gradle 8.9+

### 构建命令

```bash
# 调试版
./gradlew assembleDebug

# 运行单元测试
./gradlew testDebugUnitTest

# 发布版（需自行配置签名）
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/`。

### 国内网络环境

若访问 Gradle 官方源与 Google Maven 受限，本项目默认使用阿里云镜像
（见 `settings.gradle.kts` 与 `gradle/wrapper/gradle-wrapper.properties`）。

## 技术栈

- Kotlin 1.9.24
- AndroidX（appcompat / recyclerview / constraintlayout / lifecycle / work）
- Kotlin Coroutines
- AGP 8.5.2
- Shizuku API 13.1.5（Apache-2.0，仅 api + provider 两个制品）

**业务逻辑零第三方依赖**：评分引擎、AXML 解析器、报告序列化、
界面动效组件均为自行实现。

## 测试

```
69 个单元测试，全部通过

PermissionRulesTest  (16)  权限评分模型：组合命中、阈值边界、误报控制、规则合法性
AxmlParserTest       (22)  解析器：真实样本、组件级解析、健壮性、异常输入
ComponentAnalyzerTest(11)  组件暴露面：误报控制、绑定服务识别、启动器排除
AutoResponderTest    (10)  守护决策：阈值边界、授权与否、用户主动处置
ShellResultTest       (5)  成败判定只看 exitCode、摘要回退、超时编码
InstallMonitorTest    (5)  告警阈值行为锁定（防骚扰）
```

健壮性用例覆盖：空数据、过短数据、非 AXML 数据、截断样本、
字节翻转样本——验证解析器在任何输入下都不崩溃。

守护决策链路的用例刻意锁死的是「什么情况下**不会**动手」：
默认策略下即使评分 100 也只告警；未授权 Shizuku 时绝不尝试终止。

## 免责声明

本工具的评分基于权限组合与设备环境的**启发式评估**，
用于提示用户关注方向，**不构成对任何应用性质的判定结论**。
"某应用风险分高"不等于"该应用是恶意软件"。

Root 检测为启发式判断，存在误报与漏报的可能。

## 许可证

本应用自身代码以 [MIT License](LICENSE) 发布。

内含的第三方组件沿用其各自许可证，完整清单见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)，署名与修改说明（含 Apache 2.0 / MPL 2.0 全文）见 [`NOTICE`](NOTICE)。

## 贡献

欢迎提交 Issue 与 Pull Request。若发现评分规则存在误报或漏报，
请附带具体的应用包名与权限清单，便于复现与改进。
