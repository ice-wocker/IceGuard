# Ice 防护 混淆规则
# 保留自研 AXML 解析器（反射无关，但保持可读性便于用户审计）
-keep class com.ice.guard.core.scanner.AxmlParser { *; }
-keep class com.ice.guard.core.rules.** { *; }

# 数据模型参与 JSON 手动序列化，字段名需保持
-keep class com.ice.guard.core.report.** { *; }
-keep class com.ice.guard.data.ScanHistoryStore { *; }

# AndroidX 基础保留
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.**

# —— 安全守护（Guard）——
# 枚举常量名参与 JSON 持久化（拦截日志用 valueOf 反查），
# 一旦被混淆，历史日志与策略就会静默读不出来
-keep class com.ice.guard.core.guard.GuardChannel { *; }
-keep class com.ice.guard.core.guard.GuardVerdict { *; }
-keep class com.ice.guard.core.guard.GuardEvent { *; }
-keep class com.ice.guard.core.guard.GuardPolicy { *; }
-keep class com.ice.guard.data.GuardConfigStore { *; }
-keep class com.ice.guard.data.InterceptLogStore { *; }

# 以下组件由系统按清单中的类名实例化；
# 无障碍服务尤其关键——它由系统在独立进程中绑定，类名被改就永远连不上
-keep class com.ice.guard.core.guard.GuardService { *; }
-keep class com.ice.guard.core.guard.GuardAccessibilityService { *; }
-keep class com.ice.guard.core.guard.GuardActionReceiver { *; }
-keep class com.ice.guard.core.guard.BootGuardReceiver { *; }
-keep class com.ice.guard.core.monitor.InstallMonitorReceiver { *; }

# WorkManager 通过反射实例化 Worker
-keep class com.ice.guard.core.guard.WatchdogWorker { *; }

# —— Shizuku UserService 相关 ——
# 该服务类由 Shizuku 在独立进程中以反射方式实例化（默认构造器 / Context 构造器），
# 类名与构造器一旦被混淆/裁剪，服务就无法启动，处置功能会静默失效。
-keep class com.ice.guard.core.privilege.UserService { *; }
-keep class com.ice.guard.IUserService { *; }
-keep class com.ice.guard.IUserService$Stub { *; }
-keep class com.ice.guard.IUserService$Stub$Proxy { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
