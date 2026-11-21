# ============================================================
# R8 规则：**压缩开、混淆关**（2026-11-04）
#
# 目标：
#   - 让 R8 执行 shrink（删未引用代码）+ optimize（优化字节码）
#     → 体积从 32MB 降到合理区间（主要是裁掉 hugeicons 里没用到的 5000+ 图标）
#   - 但**不重命名**任何类/方法/字段
#     → 崩溃栈保持完全可读，排查问题不受影响
#
# 关键就是下面这一条 -dontobfuscate。
# 注意：不能靠 `isMinifyEnabled = false` 来「关混淆」，
#      因为那样 AGP 根本不会跑 R8 的 shrink 阶段，体积不会降。
# ============================================================

-dontobfuscate

# 保留行号，崩溃栈能定位到具体源码行
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- 通用属性（反射 / 序列化需要） ----------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# ---------- 反射 / 类名查找 ----------
# ⚠ 不能写 `-keep class com.agentpad.workspace.** { *; }` ——
#   那等于把整个应用都保下来，R8 一行都删不掉，压缩完全失效（也是构建超慢的原因之一）。
#   正确做法：只保「真正需要按名字访问」的成员，其余交给 R8 收缩。

# Application / Activity / Service / Receiver / Provider 由 Manifest 引用，
# R8 会自动保留，这里显式再保一次防止误删
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper

# 自定义 View 的构造函数（XML 里按类名实例化）
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------- 无障碍服务（系统按类名绑定） ----------
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# ---------- 插件系统（Rhino JS 引擎） ----------
# Rhino 通过反射调用宿主暴露的对象
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class com.agentpad.workspace.ai.PluginRuntime { *; }

# ---------- Shizuku / libadb ----------
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
-dontwarn io.github.muntashirakon.adb.**

# ---------- SSH (JSch) ----------
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ---------- jsoup ----------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---------- BetterText / Haze / HugeIcons ----------
-keep class me.rerere.bettertext.** { *; }
-dontwarn me.rerere.bettertext.**
-keep class dev.chrisbanes.haze.** { *; }
-dontwarn dev.chrisbanes.haze.**
# HugeIcons：按属性名反射取图标属性
-keep class me.rerere.hugeicons.** { *; }
-dontwarn me.rerere.hugeicons.**

# ---------- Kotlin / 协程 ----------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keep class kotlin.Metadata { *; }

# ---------- 序列化（JSON 字段名不能改） ----------
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# ---------- 枚举（values()/valueOf() 反射） ----------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- 原生方法 ----------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- DexKit（JNI + flatbuffers，2026-11-16） ----------
# ⚠ 为什么必须显式 keep：
#   云构建 run 43754414524 出的包里 DexKitBridge / DexKitLoader / flatbuffers
#   全是 0 命中 —— R8 判定「没有任何地方引用 DexKit」就整块删掉了。
#   （当时确实还没接调用点，删得没错；但一旦接上工具，这个 keep 是保命的。）
#
#   这里要保的是：
#     ① DexKitBridge 的 native 方法：JNI 是**按符号名**找的
#        （Java_org_luckypray_dexkit_DexKitBridge_nativeXxx），
#        类没了或方法被改，符号就对不上 → UnsatisfiedLinkError。
#     ② schema 类：flatbuffers 用反射/字节偏移读写，R8 的字段裁剪会让布局错位。
#     ③ 我们自己的 DexKit/DexKitLoader/DexKitRelease 入口。
-keep class org.luckypray.dexkit.DexKitBridge { *; }
-keep class org.luckypray.dexkit.schema.**{ *; }
-dontwarn org.luckypray.dexkit.**
-keep class com.agentpad.workspace.ai.DexKit { *; }
-keep class com.agentpad.workspace.ai.DexKitLoader { *; }
-keep class com.agentpad.workspace.ai.DexKitRelease { *; }
# flatbuffers 运行时（Table/FlatBufferBuilder 等）同样走反射与偏移
-keep class com.google.flatbuffers.** { *; }
-dontwarn com.google.flatbuffers.**

# ---------- smali / baksmali / dexlib2（2026-11-16） ----------
# ⚠ 为什么必须 keep：
#   · smali 内部用 antlr 生成的 parser（smaliParser$DFA*），按类名反射不到就崩
#   · dexlib2 大量使用接口 + 泛型，R8 裁剪会破坏实现绑定
#   · baksmali/smali 的 Options 类全是 public 字段，被裁剪后跑起来就是 NoSuchFieldError
-keep class org.jf.** { *; }
-dontwarn org.jf.**
-keep class org.antlr.** { *; }
-dontwarn org.antlr.**
-keep class com.beust.jcommander.** { *; }
-dontwarn com.beust.jcommander.**
-keep class org.abego.treelayout.** { *; }
-dontwarn org.abego.treelayout.**

# ⚠ R8 报 Missing class java.awt.*（云构建 run 43850050818）：
#   stringtemplate 里带了一个 Swing 的调试 GUI（org.stringtemplate.v4.gui.STViewFrame），
#   它 import 了 java.awt.Component/Container/GridBagLayout/Window 等 ——
#   Android 根本没有 AWT，R8 就报 Missing class 并中断构建。
#   这个 GUI 类我们永远不用（smali 只用到模板引擎核心），直接 dontwarn 忽略。
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.stringtemplate.v4.gui.**
-dontwarn org.stringtemplate.v4.compiler.**

# ---------- apksig（重新签名） ----------
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# ---------- 我们自己的逆向链路 ----------
-keep class com.agentpad.workspace.ai.SmaliToolchain { *; }
-keep class com.agentpad.workspace.ai.ApkRepack { *; }
-keep class com.agentpad.workspace.ai.ReverseTools { *; }

# ---------- Parcelable ----------
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---------- 减少警告噪音 ----------
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
-dontwarn org.w3c.dom.**
-dontwarn org.xmlpull.v1.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn kotlin.Unit
-dontwarn kotlin.jvm.internal.**
