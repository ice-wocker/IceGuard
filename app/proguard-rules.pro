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
