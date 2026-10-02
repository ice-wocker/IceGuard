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
| **新装应用自动检测** | 监听 `PACKAGE_ADDED`，新应用装完立即体检，高危则本地告警 | 系统广播 + 权限组合评分 |
| **应用处置（可选授权）** | 停用 / 卸载 / 清除数据 / 撤销权限已标记的应用 | Shizuku（ADB 级权限）`pm` 命令 |

### 明确不做的功能

以下能力在非 Root 的现代 Android 上第三方应用**无法实现**，因此本项目不提供：

- ❌ 实时后台拦截 / 终止恶意进程（系统隔离了应用间进程监控；即便有 ADB 权限也读不到别的应用内存）
- ❌ 云端病毒库比对（本项目不联网，也不收集样本）
- ❌ 用无障碍服务模拟点击"清理"（这正是许多安全应用滥用的高危权限）
- ❌ 安装前阻止安装（系统没有给第三方应用这个 API）

**关于"处置"的诚实边界**：授权 Shizuku 后拿到的是 shell（ADB，uid 2000）级权限，
能做的是**装后处置**——`pm disable-user` 停用、`pm uninstall` 卸载、
`pm clear` 清除数据、`pm revoke` 撤销权限。
这不是"实时拦截"，但它是非 Root 手机上真能生效的最强手段。

## 核心设计

### 权限组合评分模型

单个高危权限判别力有限——绝大多数正常应用都需要存储权限。
真正有判别力的是**权限组合**。

Ice 防护内置 9 条组合规则，例如：

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

解析器正确性由**真实样本**验证：测试夹具 `real_manifest.axml` 是 AAPT2
编译本工程产生的二进制清单（6228 字节），期望值取自 `aapt2 dump xmltree` 输出。

## 权限说明

本应用声明三项权限：

| 权限 | 用途 | 必要性 |
|---|---|---|
| `QUERY_ALL_PACKAGES` | Android 11+ 枚举已安装应用 | 权限审计功能的前提 |
| `READ_EXTERNAL_STORAGE`（≤ Android 12） | 读取存储中的 APK 文件 | 安装包扫描功能 |
| `POST_NOTIFICATIONS`（Android 13+） | 新装应用高危时发本地提醒 | 自动检测告警 |

处置功能**不通过声明系统权限实现**，而是经用户显式授权后借用 Shizuku 的 shell 权限，
可随时在 Shizuku 应用中撤销。

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
- AndroidX（appcompat / recyclerview / constraintlayout / lifecycle）
- Kotlin Coroutines
- AGP 8.5.2

**业务逻辑零第三方依赖**：评分引擎、AXML 解析器、报告序列化、
界面动效组件均为自行实现。

## 测试

```
22 个单元测试，全部通过

PermissionRulesTest (9)  权限评分模型：组合命中、阈值边界、误报控制
AxmlParserTest     (13)  解析器：真实样本、健壮性、异常输入
```

健壮性用例覆盖：空数据、过短数据、非 AXML 数据、截断样本、
字节翻转样本——验证解析器在任何输入下都不崩溃。

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
