# R8 规则（release 构建使用）。
#
# 本工程没有使用反射式框架（无 Gson / Room / Retrofit / kotlinx.serialization，
# 持久化用的是 SharedPreferences + org.json，全是直调），所以规则本来就该很精简。
# 下面每一条都对应一个具体的运行时需求，不是照抄模板。

# --- 不混淆类名 -----------------------------------------------------------
# 这是权衡后的选择，不是偷懒。
#
# 混淆能再省几百 KB，但它会把类名改短（Compose、AndroidX 的类也会一起改），
# 于是「关键类到底还在不在」这件事就无法静态验证了 ——
# 第一版开着混淆构建出来是 1.85 MB，我在 dex 里搜
# `androidx/compose/runtime/`、`androidx/activity/`、`material/icons/` 全是 0 次，
# 分不清是「被裁掉了」还是「被改名了」。
#
# 而这个包我**没法在真机上跑**，只能靠静态检查确认 R8 没裁错东西。
# 保留原始类名，这个检查才成立。本工程没有任何反射式按名查找，
# 不混淆不会有功能损失，代价只是体积略大。
-dontobfuscate

# --- osmdroid -------------------------------------------------------------
# 整包保留。它在 6.1.18 里有多处绕路：瓦片源实例会被 provider 缓存后按名字比对、
# Configuration 从 SharedPreferences 逐键读取、部分内容提供者走反射。
# osmdroid 总共才几百 KB，不是体积大头 —— 为它省这点空间去冒运行时崩溃的风险不值得。
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# --- 注解与泛型签名 ---------------------------------------------------------
# Compose 运行时、Kotlin 协程挂起点恢复都要读注解和 Signature 信息，
# 裁掉它们不会在编译期报错，只在运行时炸。
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# --- 通知 ----------------------------------------------------------------
# MockLocationService 的前台通知与 NotificationActionReceiver 通过
# PendingIntent 跨进程回传；相关类若被内联重命名，点击通知按钮会静默失效。
-keep class com.trailrun.mockgps.service.NotificationActionReceiver { *; }
-keep class com.trailrun.mockgps.service.MockLocationService { *; }

# --- Kotlin 元数据 ---------------------------------------------------------
# 协程与 suspend 函数恢复需要，缺了会在「运行时反射」这条路上出问题。
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# --- 排除困扰 -------------------------------------------------------------
# 这些库的注解引用在裁剪后常常找不到，但运行时并不需要它们。
-dontwarn javax.annotation.**
-dontwarn kotlin.**
-dontwarn org.slf4j.**
