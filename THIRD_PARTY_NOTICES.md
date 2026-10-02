# 第三方组件清单与署名

本项目（Ice 防护 / IceGuard）以 MIT 许可证发布，自身代码版权归项目作者所有。
以下列出实际并入本应用构建产物的第三方组件，以及各自的许可证与署名要求。

> 说明：本清单**只列实际用到的组件**。未使用的组件不会出现在这里——
> 虚假署名和虚假声明一样，都是对本项目"可验证"原则的破坏。

## 一、直接依赖（并入 APK）

| 组件 | 版本 | 许可证 | 版权持有者 | 用途 |
|---|---|---|---|---|
| AndroidX Core KTX | 1.13.1 | Apache-2.0 | The Android Open Source Project | Kotlin 扩展 |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 | The Android Open Source Project | 兼容性支持 |
| AndroidX RecyclerView | 1.3.2 | Apache-2.0 | The Android Open Source Project | 列表组件 |
| AndroidX ConstraintLayout | 2.1.4 | Apache-2.0 | The Android Open Source Project | 布局 |
| AndroidX Lifecycle Runtime KTX | 2.8.4 | Apache-2.0 | The Android Open Source Project | 协程生命周期 |
| Kotlin Standard Library | 1.9.24 | Apache-2.0 | JetBrains s.r.o. | 语言运行时 |
| kotlinx.coroutines | 1.8.1 | Apache-2.0 | JetBrains s.r.o. | 协程 |
| **Shizuku API** (`dev.rikka.shizuku:api`) | 13.1.5 | **Apache-2.0** | RikkaApps | 调用 Shizuku 服务 |
| **Shizuku Provider** (`dev.rikka.shizuku:provider`) | 13.1.5 | **Apache-2.0** | RikkaApps | Shizuku 接入点 |

## 二、测试依赖（不进入 APK）

| 组件 | 版本 | 许可证 |
|---|---|---|
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |

## 三、关于 Shizuku 的重要声明

### 3.1 我们只用了它的官方 API，没有内嵌应用本体

Ice 防护通过 Maven 依赖引入 Shizuku 官方的 `api` 与 `provider` 两个制品，
**没有**将 Shizuku 应用本体（`moe.shizuku.privileged.api`）打进 APK，
也没有对其重新打包或改名分发。

Shizuku 项目的作者（RikkaApps）在 README 中明确请求：
**不要将 Shizuku 重新打包或以其他名字分发**。本项目遵守这一要求。

### 3.2 Shizuku 应用本体需用户自行安装

处置功能要求用户自行安装 Shizuku 应用，并通过
「开发者选项 → 无线调试」完成一次配对授权。
这一步**无法由本应用代劳**——Shizuku 需要以 shell 身份启动服务进程，
而"以 shell 身份启动"必须由用户操作 ADB 或无线调试完成。

### 3.3 我们拿到了什么权限，又拿不到什么

经 Shizuku 授权后，本应用获得的是 **shell（ADB，uid 2000）级权限**：

- ✅ 可以：`pm disable-user` 停用应用、`pm uninstall` 卸载应用、
  `pm clear` 清除数据、`pm revoke` 撤销权限
- ❌ 不可以：查看/终止其他应用的进程、读取其他应用内存、实时行为监控

因此本项目**不提供**"实时拦截并终止病毒进程"这类功能——
不是偷懒，是权限模型不允许。任何宣称能在非 Root 手机上做到的产品，
要么依赖 Root，要么在夸大其词。

## 四、Apache-2.0 许可证全文

见 [`NOTICE`](NOTICE)。所有 Apache-2.0 组件的许可证全文随本仓库分发。

## 五、修改说明

本项目的所有第三方组件均以**未经修改**的原始制品形式引入，
未对其源码做过任何改动，因此不涉及"修改声明"义务。

`app/src/main/aidl/com/ice/guard/IUserService.aidl` 与
`app/src/main/java/com/ice/guard/core/privilege/UserService.kt`
是本项目**自行编写**的 UserService 实现（用于配合 Shizuku 运行 shell 命令），
并非 Shizuku 的代码，版权归本项目所有，以 MIT 许可证发布。

---

如需审计，可执行以下命令核对实际依赖：

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```
