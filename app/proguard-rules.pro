# 组件由 manifest 保活（AGP 生成的 aapt_rules 已覆盖），这里只做体积优化。
-allowaccessmodification
-repackageclasses ''

# 不用反射，无需保留额外成员；仅压制可选注解告警。
-dontwarn org.jetbrains.annotations.**
