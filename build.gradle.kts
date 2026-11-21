plugins {
    // AGP 9.4 起，根 build.gradle.kts 里必须用 `apply false`。
    // 直接在根项目应用会报：
    //   "Android Gradle Plugin has been applied at the root build file.
    //    Please consider not applying in the root (use `apply false`)."
    // 真正的应用发生在 app/build.gradle.kts 的 plugins { id("com.android.application") }。
    id("com.android.application") version "9.4.0" apply false
    // Kotlin 2.4.21 —— 与 RikkaHub 3.1.1 完全对齐（其 libs.versions.toml:5）。
    // 只有 2.4.x 的 compose 编译器才能读 material3 1.5.0-beta02 的元数据。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
