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
