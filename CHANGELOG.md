# 更新日志 / Changelog

遵循 [语义化版本](https://semver.org/lang/zh-CN/)。只记录**用户可感知的改动**——
纯版本号提升不单独占用一个版本。

## [1.1.0] - 2026-10-02

从"体检工具"升级为"持续守护"。核心变化是把**检测**与**处置**串成一条自动化链路：
事件一到就判定，判定通过就终止。

### 新增：安全守护（四条检测通道）

| 通道 | 触发时机 | 特殊权限 |
|---|---|---|
| 安装 / 更新 | `PACKAGE_ADDED` · `PACKAGE_REPLACED` | 无 |
| 开机自检 | `BOOT_COMPLETED` | 无 |
| 前台唤醒 | 前台应用切换（轮询，约 1 秒） | 需「使用情况访问」 |
| 无障碍前台事件 | 窗口状态变化 | 需手动开启无障碍服务 |
| 周期巡检（兜底） | WorkManager，最小间隔 15 分钟 | 无 |

四条通道汇入同一条响应管线 `AutoResponder`：取分 → 读策略 → 决策 →
（可选）终止 → 落日志 → 发通知。

### 新增：秒级终止

- `am force-stop` 立即结束目标应用全部进程，且不会被系统自动拉起；
- 按策略追加 `pm disable-user` 冻结，使其彻底无法运行；
- **亚秒级生效，且日志记录实际耗时与命令返回**，可在设备上核验。

**必须说清的能力边界**：终止是真的，但"监控到病毒发作"做不到——
Android 沙箱隔离了应用间观测。因此本项目提供的是**事件驱动的秒级响应**，
不是"实时行为监控"。README 中已单列一节说明这一点。

### 新增：检测深度

- **组件级清单分析**：自研 AXML 解析器现在会解析
  `activity` / `service` / `receiver` / `provider` 四类组件及其
  `android:exported`、`android:permission`、`intent-filter` 中的 `action`；
  并识别应用**自己实现**的高敏感绑定服务（无障碍 / 通知读取 / VPN / 输入法 /
  设备管理器 / 通话筛查）。
  精度优先：启动器入口与受权限保护的组件不计入"对外暴露"，避免刷屏。
- **安装来源与签名检测**：识别侧载（非应用商店安装）、`debuggable`、
  `testOnly`，并给出签名证书 SHA-256 供用户自行核对（不做黑名单比对）。
- **组合规则 9 → 18 条**：新增覆盖"无联网也能作案"（短信 + 无障碍、
  录音 + 摄像头 + 后台定位）与"下载-投放-安装"链条
  （联网 + 安装、设备管理器 + 安装）。
- **本地拦截日志**：每次判定与处置的时间、通道、动作、命令返回全部留痕，
  上限 200 条，只存本机。

### 新增：守护中心与首页守护卡

- 守护模式、自动终止、阈值（40–95）、终止后冻结、前台监听、无障碍通道均可单独开关；
- 首页新增守护状态卡，如实显示"守护中 / 未开启"与真实的已处置次数。

### 安全设计（刻意保守）

- **自动终止默认关闭**：默认状态下只提醒、不处置；
- **未授权 Shizuku 时无法开启自动终止**，界面直接拦下并指路，
  而不是让用户以为"开了就安全"；
- **无障碍通道只观察不操作**：`canRetrieveWindowContent=false`、
  未声明 `canPerformGestures`、代码中不存在 `dispatchGesture` / `performAction`，
  只监听 `typeWindowStateChanged` 取包名；
- **仍然不申请 `INTERNET` 权限**，可用 `aapt2 dump permissions` 自行验证。

### 性能

- 前台通道每次窗口切换都会走响应管线，因此体检结果带 **10 分钟缓存**、
  处置带 **30 秒节流**，避免拖慢整机。

### 权限变化

新增 `RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`、
`FOREGROUND_SERVICE_SPECIAL_USE`、`PACKAGE_USAGE_STATS`（特殊权限，需手动授予）。
均为可选功能的支撑，默认关闭。

### 依赖变化

新增 `androidx.work:work-runtime-ktx:2.9.1`（Apache-2.0），用于周期巡检排程。

### 工程

- R8 规则新增守护相关保留项：枚举常量名参与 JSON 持久化，
  `GuardAccessibilityService` / `GuardService` / `WatchdogWorker` 等由系统或
  WorkManager 反射实例化，混淆后会导致守护静默失效；
- 单元测试 32 → 69（新增 `AutoResponderTest`、`ComponentAnalyzerTest`，
  并扩充 `PermissionRulesTest` 与 `AxmlParserTest` 的组件级用例）。

## [1.0.2] - 2026-10-02

这一版是第一个**功能真的有变化**的版本。1.0.1 只改了 `versionCode` / `versionName`
两行，App 行为与 1.0.0 完全一致。

### 新增

- **新装应用自动检测**：监听 `PACKAGE_ADDED` 广播，新应用装完立即按权限组合评分，
  风险分 ≥ 65 发本地通知。更新安装（`EXTRA_REPLACING`）不打扰。
- **应用处置（需用户授权 Shizuku）**：`pm disable-user` 停用、`pm uninstall` 卸载、
  `pm clear` 清除数据、`pm revoke` 撤销权限。
- **权限与处置引导页**：把「无线调试配对」6 步摊开写清楚，按授权状态提示下一步；
  授权后可查询「已停用的应用」，用数据验证处置真的生效。
- **首页新增「权限与处置」入口**，审计页每个应用新增「处置」入口，
  破坏性操作（卸载 / 清数据）二次确认。

### 权限变化

- 新增 `POST_NOTIFICATIONS`（Android 13+），用于新装高危应用提醒。
- 新增对 Shizuku 的 `moe.shizuku.manager.permission.API_V23` 依赖声明。

**仍然不申请 `INTERNET` 权限**，可用 `aapt2 dump permissions` 自行验证。

### 明确做不到的（本次也一并写进 README）

- ❌ 实时后台拦截 / 终止恶意进程 —— 系统隔离了应用间进程监控，
  即便有 ADB 权限也读不到别的应用内存
- ❌ 安装前阻止安装 —— 系统没有给第三方应用这个 API
- ⚠️ 「内置 Shizuku 就等于有权限」不成立：Shizuku 需以 shell 身份启动服务进程，
  「以 shell 身份启动」必须由用户操作 ADB 或无线调试完成。
  能内置的是引导，不是权限。

### 工程

- R8 规则保留 `UserService` / AIDL / `rikka.shizuku.*`，避免反射实例化的类被裁掉；
  CI 增加 release 构建专门验证这一点（`assembleRelease`）。
- 单元测试 22 → 32（新增 `ShellResultTest` 5 个、`InstallMonitorTest` 5 个）。
- `NOTICE` / `THIRD_PARTY_NOTICES.md`：如实标注 Shizuku 署名，
  并声明**未内嵌、未重打包 Shizuku 应用本体**。

## [1.0.1] - 2026-10-02

- 仅版本号提升（`versionCode` 1 → 2）。**无任何功能改动。**
- 顺带落地的工程改动：Gradle Wrapper 修复、tag 自动发版流水线、README 徽章、
  第三方许可证清单。

## [1.0.0] - 2026-10-01

- 首个版本：应用权限审计、安装包静态扫描、设备环境检测、安全体检评分、报告导出。
