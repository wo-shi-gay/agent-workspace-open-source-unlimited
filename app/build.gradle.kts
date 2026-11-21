import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名配置：从 local.properties 读取，不把密码写进版本库。
// 本项目 debug 包必须用 CN=AgentPad / alias=a 的自有密钥签名，
// 否则无法覆盖升级已安装的线上版本。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) FileInputStream(f).use { load(it) }
}

// ============================================================
// 云构建（GitHub Actions）兜底
//
// 云端没有 local.properties（里面存着签名密码，绝不进版本库）。
// 所以每个 signing.* 都允许用同名、点换下划线的环境变量覆盖：
//   signing.storeFile     -> SIGNING_STORE_FILE
//   signing.storePassword -> SIGNING_STORE_PASSWORD
//   signing.keyAlias      -> SIGNING_KEY_ALIAS
//   signing.keyPassword   -> SIGNING_KEY_PASSWORD
//
// 本地行为完全不变：先读 local.properties，读不到才看环境变量。
// ============================================================
fun localOrEnv(propKey: String, envKey: String): String? =
    localProps.getProperty(propKey) ?: System.getenv(envKey)

// R8 开关：本地默认关（内存不够跑不动），云构建用 -Pr8.minify=true 打开。
// 用法：gradle assembleRelease -Pr8.minify=true
val r8Minify = (project.findProperty("r8.minify") as String?)?.toBoolean() ?: false

// ============================================================
// 版本号：直接写死在源码里，构建时不传任何参数。
//
// ⚠ 2026-11-04 血泪教训
//   之前默认值留 47 / 4.7，靠命令行 -Papp.versionCode=xx 临时覆盖。
//   结果：某次构建忘了传参数，产出包的 versionCode 掉回 47，
//   比已发布的 50 还低 —— 装不上（Android 拒绝降级），
//   还让主人按文件名去加固，加固到了错版本的包。
//
//   规则（主人拍板）：
//     · 构建 ≠ 发版。自测构建也读这里的值，不再靠命令行传。
//     · 只有正式发版才改这两个数字，改完提交。
//   -P 覆盖仍然保留，但只用于「临时试一个号」，不是日常路径。
// ============================================================
val versionCodeOverride = (project.findProperty("app.versionCode") as String?)?.toIntOrNull()
val versionNameOverride = project.findProperty("app.versionName") as String?

/** 当前发布版本。改这里 = 发新版。 */
val APP_VERSION_CODE = 66
val APP_VERSION_NAME = "5.2.2-open-source"

android {
    // 4.7 换包名：com.agentpad.newapp -> com.agentpad.workspace
    // 换包名的代价：老用户无法覆盖安装，必须卸载重装；
    // 卸载前必须先用 4.6 的「数据迁移」导出，装完 4.7 再导入。
    namespace = "com.agentpad.workspace"
    // ⚠ 2026-11-16：规避 Android 17（targetSdk 37+）的 Safer DCL 政策。
    //
    //   政策原文（主人截图）：targetSdkVersion >= 37 时，「更安全的动态代码加载」
    //   从 DEX/JAR 扩展到原生库，要求所有经 System.load(absolutePath) 加载的 .so：
    //     ① 加载前必须被标记为只读，否则抛 UnsatisfiedLinkError；
    //     ② 必须通过签名校验，缺签名同样加载失败；
    //     ③ 只能从私有目录加载（/sdcard 被标记非可执行，无法映射可执行页）。
    //
    //   我们的 DexKit 走「下载模式」：so 不进 APK，运行时下到私有目录再 System.load，
    //   正好是这条政策要管的场景。把 targetSdk 降到 30（Android 11）即规避：
    //   Safer DCL 只在 targetSdk >= 37 时启用。
    //
    //   为什么是 30 而不是 29：minSdk 已是 26，而 30 是「按应用请求所有文件访问」
    //   （MANAGE_EXTERNAL_STORAGE 走设置页授权）这一套的分界，代码里 MainActivity
    //   `ensureStoragePermission()` 已按 >= 30 / < 30 两支都写好了，30 正好落在已实现的分支。
    //
    //   compileSdk 仍保持 37：编译期用新 API 不受影响，且 compose-bom 2026.09.00 /
    //   material3 1.5.0-beta02 需要高版本 compileSdk（AGP 会告警 targetSdk < compileSdk，
    //   属预期，不是错误）。
    //   与 RikkaHub 3.1.1 对齐的是 compileSdk，targetSdk 本就是我们自己的选择。
    compileSdk = 37

    signingConfigs {
        val storePath = localOrEnv("signing.storeFile", "SIGNING_STORE_FILE")
        if (storePath != null) {
            create("appDebug") {
                storeFile = file(storePath)
                storePassword = localOrEnv("signing.storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = localOrEnv("signing.keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = localOrEnv("signing.keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.agentpad.workspace.opensource"
        minSdk = 26
        // ⚠ targetSdk 30（Android 11）：规避 Android 17 的 Safer DCL（见上方长注释）。
        // 目标系统 = Android 10/11 档；旧机（26~29）行为不变，新机也不再触发只读+签名校验。
        targetSdk = 30
        versionCode = versionCodeOverride ?: APP_VERSION_CODE
        versionName = versionNameOverride ?: APP_VERSION_NAME
        // 只发布 arm64-v8a：与线上已发布加固包保持一致。
        // 依赖库（androidx.graphics.path / libadb spake2）自带的
        // armeabi-v7a / x86 / x86_64 一律不打包，避免体积无谓增加。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }

    buildTypes {
        debug {
            // 用自有密钥覆盖升级线上包；local.properties 缺失时回退默认 debug 签名
            signingConfig = signingConfigs.findByName("appDebug")
            // ⚠ debug 不做压缩：AGP 对 debuggable 构建强制禁用 R8 代码压缩，
            // 设 isMinifyEnabled = true 只会产生警告且不生效。
            // 需要瘦身包请构建 release（见下方 release 段）。
            isMinifyEnabled = false
        }
        release {
            // ⚠ 2026-11-04：R8 在本机（可用内存 ~1.2GB）无法在系统杀进程前跑完，
            // 已放弃。体积问题通过「移除 hugeicons 21MB 依赖」从根上解决，
            // 不再依赖 R8 收缩。release 与 debug 保持一致（不压缩、不混淆）。
            signingConfig = signingConfigs.findByName("appDebug")
            // 云构建（16GB 内存）用 -Pr8.minify=true 打开；本地默认 false，行为不变。
            isMinifyEnabled = r8Minify
            isShrinkResources = r8Minify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // PTY native bridge is staged in src/main/cpp; enable externalNativeBuild only when a complete NDK toolchain is available.

    // ⚠ 2026-11-04 关闭 lintVital。
    //
    // `lintVitalRelease` 是 release 构建默认跑的强制检查，但它：
    //   1. 要先把所有类交给 lint 做全量分析（Compose 项目特别慢）；
    //   2. 在 ARM 手机上单次 10+ 分钟，经常和 R8 一起把进程拖到被系统杀；
    //   3. 只产出警告，**不改变 APK 产物**。
    // 关掉它不影响构建正确性，需要时单独跑 `gradle lint` 即可。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs {
            // Force lib/*.so to be extracted to disk (nativeLibraryDir). With
            // extractNativeLibs=false the so are loaded from within the APK and
            // never exist as real files, so proot can't be exec()'d.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Shizuku：通过 ADB/root 授权的系统级权限通道
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // libadb-android：无线调试配对码直连（adb pair / adb connect），不依赖 Shizuku
    implementation("com.github.MuntashirAkon:libadb-android:1.0.1")

    // 插件系统 JS 引擎（路由 B）：Rhino 纯 Java 实现，无原生库、全 ABI 通吃。
    // 开源副本从 Maven Central 获取 Rhino，避免分发本地依赖 JAR。
    implementation("org.mozilla:rhino:1.7.15.1")

    // Kept minimal on purpose: FileProvider (core) and the ActivityResult
    // API (activity) are the only androidx pieces the app actually uses.
    // appcompat was removed — it dragged in fragment/lifecycle/coroutines
    // (~6MB of dex) for nothing; activities extend plain android.app.Activity.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    // Compose 依赖与 RikkaHub 3.1.1 完全对齐
    // （其 gradle/libs.versions.toml:6 composeBom="2026.09.00"、:7 material3="1.5.0-beta02"）
    // 前提是 compileSdk 37 + AGP 9.1.0+，本机已满足（SDK 37 已装、AGP 9.4.0）。
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    // material3 1.5.0-beta02：与 RikkaHub 同版本。
    // 这个版本才公开 MotionScheme / ExperimentalMaterial3ExpressiveApi，
    // 因此可以真正调用 MaterialTheme.motionScheme.xxxSpec()，
    // 不再需要 ui/ExpressiveMotion.kt 那种抄常量的替代方案。
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    // 图标库：只保留 core（18 个常用图标）。
    // extended 一个库 34MB / 11123 个类，把 classes.dex 撑到 41.9MB。
    // 项目实际只用到 108 个图标，其中 18 个 core 已有，
    // 剩下 90 个已从 extended 里用反射导出成真实 ImageVector 源码，
    // 放在 src/main/java/androidx/compose/material/icons/ 下（同名同包，import 零改动）。
    implementation("androidx.compose.material:material-icons-core:1.7.3")
    // SSH 客户端，用于「远程工作区」真正连上服务器。
    // 用 mwiede 的 JSch 分支（原版 JSch 早已停止维护，不支持新算法）。
    // 版本 2.28.8 与 RikkaHub 3.1.1 的 libs.versions.toml 一致。
    // 纯 Java 实现，无原生库，全 ABI 通吃，体积约 300KB。
    implementation("com.github.mwiede:jsch:2.28.8")
    // HTML 解析。用于必应搜索（照抄 RikkaHub BingSearchService 的 li.b_algo 解析）
    // 以及 WebView 抓取失败时的纯 HTTP 兜底（无 JS 渲染的静态页）。
    implementation("org.jsoup:jsoup:1.18.3")

    // ============================================================
    // 以下为「照抄 RikkaHub 3.1.1 界面」所需的依赖（2026-11-03 追加）
    // 版本号一律与 rikkahub-master/gradle/libs.versions.toml 对齐，保证行为一致。
    // ============================================================

    // ⚠ 2026-11-04 已移除巨大依赖：
    //   implementation("com.github.rikkahub:hugeicons-compose:1.5")   ← 21MB，5000+ 图标
    // 项目只用到 12 个图标，这些图标的 ImageVector 路径数据已从该库字节码
    // 提取成本地源码：ui/icons/HugeIconsLocal.kt（约 21KB）。
    // 移除后 APK 直接瘦 20MB，R8 也不必再处理这 5000+ 个类（构建时间大幅下降）。
    // 图标外观与原库完全一致（同一份 path 数据）。

    // Haze 毛玻璃 / 玻璃拟态（参考源码 ChatInputHaze.kt、AssistantBackground）
    // 其 libs.versions.toml:48 haze = "2.0.1"
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur-material3:2.0.1")
    implementation("dev.chrisbanes.haze:haze-glass:2.0.1")
    implementation("dev.chrisbanes.haze:haze-glass-material3:2.0.1")

    // BetterText：Markdown / LaTeX / 代码高亮渲染（参考源码 MarkdownBlock）
    // 其 libs.versions.toml:4 bettertext = "v0.1.3"
    implementation(platform("com.github.re-ovo.bettertext:bom:v0.1.3"))
    implementation("com.github.re-ovo.bettertext:text")
    implementation("com.github.re-ovo.bettertext:markdown")
    implementation("com.github.re-ovo.bettertext:latex")
    implementation("com.github.re-ovo.bettertext:highlight")

    // ============================================================
    // androidsvg —— 模型 LOGO 渲染（2026-11-09 追加）
    //
    // 为什么要它：照抄 RikkaHub 的「按模型名自动匹配 LOGO」功能
    // （其 AIIconMatcher.kt:6 起 55 条正则 + assets/icons/ 55 个 SVG）。
    // RikkaHub 用 coil3 + coil-svg 渲染，那套要 1~2MB；
    // androidsvg 只有约 200KB，纯 Java 无原生库，且完整支持 linearGradient
    // （gemini 这类多路径渐变图标也能原样还原）。
    //
    // 为什么不转成 Compose ImageVector：单色图标能转，带渐变的会失真，
    // 55 个图标里有一批是渐变款，转过去外观就不对了。
    // ============================================================
    implementation("com.caverock:androidsvg-aar:1.4")

    // ============================================================
    // snakeyaml —— Skill 的 SKILL.md frontmatter 解析（2026-11-15 追加）
    //
    // 照抄 RikkaHub 3.2.0 的 data/skills/SkillFrontmatterParser.kt，
    // 它用 org.yaml.snakeyaml 的 SafeConstructor 读 YAML frontmatter，
    // 并带 isAllowDuplicateKeys=false / maxAliasesForCollections=50 /
    // nestingDepthLimit=50 / codePointLimit=1MB 四项防炸限制。
    // 纯 Java 无原生库，约 300KB，全 ABI 通吃。
    // ============================================================
    implementation("org.yaml:snakeyaml:2.3")

    // ============================================================
    // flatbuffers —— DexKit 查询报文的编解码（2026-11-16 追加，A 方案）
    //
    // 为什么需要：DexKit 的 JNI 查询接口签名是 `(J[B)[B` ——
    //   传 flatbuffers 二进制进、返二进制出。要"用字符串批量反查被混淆的
    //   类/方法"就必须能构造/解析这个报文。
    //
    // 版本与 DexKit 官方一致（其 dexkit-android/build.gradle 里写的就是
    //   com.google.flatbuffers:flatbuffers-java:23.5.26，且 schema/README
    //   要求 flatc 23.5.26 —— 运行时要与编译期同版本，否则二进制布局会错）。
    //
    // 体积：约 90KB，纯 Java 无原生库。
    // 收益：省掉 apktool.jar 的 4.3MB（解包/反编译走原生），净赚。
    // ============================================================
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")

    // ============================================================
    // smali / baksmali / dexlib2 —— dex ↔ smali 互转（2026-11-16）
    //
    // 为什么需要：DexKit 只能「定位」被混淆的类/方法，**不能改**。
    //   要做到「改逻辑 → 回编译成完整 APK」，必须有：
    //     dex --baksmali--> smali 文本   （人/AI 能读能改）
    //     smali --smali--> dex           （改完编译回去）
    //   这就是 baksmali + smali 两个件。
    //
    // 为什么用 Maven 而不是塞 libs/*.jar：
    //   实测这三个件的依赖是 guava + antlr-runtime + jcommander + stringtemplate，
    //   手工塞 jar 很容易漏（漏一个就是运行时 NoClassDefFoundError）。
    //   交给 Gradle 解析传递依赖最稳。
    //
    // API（已用 javap 逐个核实，见 ai/SmaliToolchain.kt）：
    //   dex → smali : Baksmali.disassembleDexFile(DexFile, File, int, BaksmaliOptions)
    //   smali → dex : Smali.assemble(SmaliOptions, String...)   // outputDexFile 指定输出
    //
    // 体积：dexlib2 ≈ 1.1MB + smali ≈ 0.3MB + baksmali ≈ 0.12MB + 传递依赖（guava/antlr）
    //   实测总增量约 3~4MB dex —— 相比 apktool 那 187MB 的怪物仍小两个数量级。
    //   比「走 proot 里 java -jar apktool」快得多：没有 ptrace 系统调用翻译开销。
    // ============================================================
    implementation("org.smali:dexlib2:2.5.2")
    implementation("org.smali:smali:2.5.2")
    implementation("org.smali:baksmali:2.5.2")

    // ============================================================
    // apksig —— APK 重打包后重新签名（2026-11-16）
    //
    // 为什么必须有：改完 dex 回编译出来的 APK **没签名就装不上**。
    //   而且 Android 11+（targetSdk ≥ 30）要求 v2 签名，只做 v1(JAR) 不够。
    //   apksig 是 Google 官方签名库，V1/V2/V3 一次搞定。
    //
    // 体积：约 1MB，纯 Java。
    // 注意：重签后的 APK 与原应用**签名不同**，无法覆盖安装原版
    //   （只能换包名重装，或用于自己从头编译的场景）。
    // ============================================================
    implementation("com.android.tools.build:apksig:8.7.3")
}