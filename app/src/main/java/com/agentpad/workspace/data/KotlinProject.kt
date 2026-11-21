package com.agentpad.workspace.data

import android.content.Context
import java.io.File

/**
 * Kotlin 项目会话的预置 Gradle + Kotlin/Compose 工程模板。
 *
 * 与 VideoProject 一样：创建会话时只补不存在的文件，不覆盖用户/AI 已经改过的内容。
 * 模板默认使用 App 下载环境里的两包 Gradle 工具链：
 *   /opt/gradle-toolchain-arm64
 *   /opt/gradle-home/caches（由包 2 提供 AGP/Kotlin/Compose 依赖）
 */
object KotlinProject {
    const val MAIN = "app/src/main/java/com/example/ktproject/MainActivity.kt"
    const val SETTINGS = "settings.gradle.kts"
    const val ROOT_BUILD = "build.gradle.kts"
    const val APP_BUILD = "app/build.gradle.kts"
    const val PROPERTIES = "gradle.properties"
    const val MANIFEST = "app/src/main/AndroidManifest.xml"

    /** 内置模板压缩包：源码由主人提供，App 只负责原样解压。 */
    private const val TEMPLATE_ASSET = "kotlin-project/AgentKotlin-project-source-only.zip"
    private const val TEMPLATE_ROOT = "AgentKotlin/"

    fun ensureTemplate(context: Context, sessionId: String) {
        val root = ChatStore.ensureWorkspace(context, sessionId).apply { mkdirs() }
        // 首次创建时目标文件为空；已有文件不覆盖，避免历史会话被重新打开时抹掉 AI 改动。
        runCatching {
            context.assets.open(TEMPLATE_ASSET).use { input ->
                java.util.zip.ZipInputStream(java.io.BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (entry.isDirectory || !name.startsWith(TEMPLATE_ROOT)) {
                            zip.closeEntry()
                            continue
                        }
                        val relative = name.removePrefix(TEMPLATE_ROOT)
                        if (relative.isBlank() || relative.contains("../") || relative.startsWith("/")) {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(root, relative).normalize()
                        val rootPath = root.normalize().path
                        require(target.path == rootPath || target.path.startsWith(rootPath + File.separator)) {
                            "模板路径越界：$relative"
                        }
                        if (!target.exists()) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                    }
                }
            }
        }.onFailure {
            // asset 缺失时保留 KotlinProject 的内置兜底模板，避免新会话变成空目录。
            writeIfAbsent(File(root, SETTINGS), settings())
            writeIfAbsent(File(root, ROOT_BUILD), rootBuild())
            writeIfAbsent(File(root, APP_BUILD), appBuild())
            writeIfAbsent(File(root, PROPERTIES), properties())
            writeIfAbsent(File(root, MANIFEST), manifest())
            writeIfAbsent(File(root, MAIN), mainKt())
            writeIfAbsent(File(root, "README.md"), readme())
        }
    }

    private fun writeIfAbsent(file: File, text: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(text.trimIndent() + "\n", Charsets.UTF_8)
        }
    }

    private fun settings() = """
        import org.gradle.api.initialization.resolve.RepositoriesMode

        pluginManagement {
            repositories { google(); mavenCentral(); gradlePluginPortal() }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories { google(); mavenCentral() }
        }
        rootProject.name = "KtProject"
        include(":app")
    """

    private fun rootBuild() = """
        plugins {
            id("com.android.application") version "9.4.0" apply false
            id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
        }
    """

    private fun appBuild() = """
        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.plugin.compose")
        }

        android {
            namespace = "com.example.ktproject"
            compileSdk = 37
            buildToolsVersion = "36.0.0"
            defaultConfig {
                applicationId = "com.example.ktproject"
                minSdk = 26
                targetSdk = 30
                versionCode = 1
                versionName = "1.0"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildFeatures { compose = true }
        }

        dependencies {
            implementation(platform("androidx.compose:compose-bom:2026.09.00"))
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.foundation:foundation")
            implementation("androidx.compose.material3:material3:1.5.0-beta02")
            implementation("androidx.activity:activity-compose:1.9.2")
            implementation("androidx.core:core-ktx:1.13.1")
        }
    """

    private fun properties() = """
        android.useAndroidX=true
        kotlin.code.style=official
        org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
        org.gradle.parallel=false
        org.gradle.workers.max=1
        android.aapt2FromMavenOverride=/opt/gradle-toolchain-arm64/android-sdk/build-tools/36.0.0/aapt2
    """

    private fun manifest() = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:theme="@android:style/Theme.Material.Light.NoActionBar" android:label="KtProject">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """

    private fun mainKt() = """
        package com.example.ktproject

        import android.os.Bundle
        import androidx.activity.ComponentActivity
        import androidx.activity.compose.setContent
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable

        class MainActivity : ComponentActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContent { MaterialTheme { Home() } }
            }
        }

        @Composable
        private fun Home() {
            Text("Kotlin 项目已就绪")
        }
    """

    private fun readme() = """
        # Kotlin 项目

        这是 AgentPad 预置的标准 Gradle + Kotlin/Compose Android 工程。

        ## 构建

        ```sh
        export JAVA_HOME=/opt/gradle-toolchain-arm64/jdk-17.0.21+9
        export ANDROID_HOME=/opt/gradle-toolchain-arm64/android-sdk
        export GRADLE_USER_HOME=/opt/gradle-home
        export PATH=${'$'}JAVA_HOME/bin:/opt/gradle-toolchain-arm64/gradle-9.6.0/bin:${'$'}PATH
        gradle --offline assembleDebug
        ```

        `gradle.properties` 已预置 arm64 AAPT2 路径；如果 JDK 目录实际版本名不同，先 `ls /opt/gradle-toolchain-arm64` 后修正 JAVA_HOME。
    """
}