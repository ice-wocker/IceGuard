# 更新日志 / Changelog

遵循 [语义化版本](https://semver.org/lang/zh-CN/)。只记录**用户可感知的改动**——
纯版本号提升不单独占用一个版本。

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
