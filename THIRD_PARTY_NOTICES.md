# 第三方组件与开源许可证清单 / Third-Party Notices

本文件列出 Ice 防护（IceGuard，下称"本应用"）所包含的第三方开源软件及其许可证。
本应用自身代码以 MIT 许可证发布（见 `LICENSE`）；内含的第三方组件沿用其各自许可证。

This file lists the third-party open source software included in IceGuard
and their respective licenses. IceGuard's own code is released under the MIT
License (see `LICENSE`); bundled third-party components remain under their
own licenses.

完整署名与每个被修改文件的改动说明见根目录 [`NOTICE`](NOTICE)。
本文件第三部分即其内容。

---

## 一、直接依赖（Gradle 声明，构建期引入）

以下依赖通过 `app/build.gradle.kts` 声明，均为 Apache License 2.0，
随构建产物一并分发，保留原始版权与署名声明。

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| [androidx.core:core-ktx](https://developer.android.com/jetpack/androidx) | 1.13.1 | Apache-2.0 | AndroidX 核心 Kotlin 扩展 |
| [androidx.appcompat:appcompat](https://developer.android.com/jetpack/androidx) | 1.7.0 | Apache-2.0 | 兼容性支持库 |
| [androidx.recyclerview:recyclerview](https://developer.android.com/jetpack/androidx) | 1.3.2 | Apache-2.0 | 列表视图 |
| [androidx.constraintlayout:constraintlayout](https://developer.android.com/jetpack/androidx) | 2.1.4 | Apache-2.0 | 布局 |
| [androidx.lifecycle:lifecycle-runtime-ktx](https://developer.android.com/jetpack/androidx) | 2.8.4 | Apache-2.0 | 生命周期 |
| [org.jetbrains.kotlinx:kotlinx-coroutines-android](https://github.com/Kotlin/kotlinx.coroutines) | 1.8.1 | Apache-2.0 | 协程并发 |

> Kotlin 标准库（`org.jetbrains.kotlin:kotlin-stdlib`）随 Kotlin 编译器引入，
> 许可证为 Apache-2.0。

## 二、测试依赖（不随产物分发）

| 组件 | 版本 | 许可证 |
|---|---|---|
| [junit:junit](https://github.com/junit-team/junit4) | 4.13.2 | EPL-1.0 |

## 三、内置组件（随代码并入，见 `NOTICE`）

本应用计划内置以下组件以提供设备级能力。当前版本**尚未包含**本节所列代码；
一旦并入，其许可证与署名义务即刻生效，细则见 `NOTICE` 第一部分。

| 组件 | 来源 | 许可证 | 状态 |
|---|---|---|---|
| Shizuku | https://github.com/RikkaApps/Shizuku | Apache-2.0 | 计划内置 |
| AOSP `adb` 客户端片段 | https://android.googlesource.com/ | Apache-2.0 | 计划内置 |

## 四、许可证全文

- Apache License 2.0 全文：见 `NOTICE` 附录一
- Mozilla Public License 2.0 全文：见 `NOTICE` 附录二

## 五、源码位置

- 本应用源码：仓库根目录 `app/`
- 内置组件源码：仓库根目录 `embedded/`（并入后生成）
- Native/内嵌 C++ 组件源码：`app/src/main/cpp/`（并入后生成）

## 六、免责声明

本清单力求准确，但开源许可证义务以各组件原始声明为准。
若发现遗漏或错误，请提交 Issue。
