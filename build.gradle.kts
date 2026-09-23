plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 has built-in Kotlin but bundles an older Kotlin Gradle plugin; declaring it here pins the newer one.
    // Only put it on the classpath: applying org.jetbrains.kotlin.android in a module is an error under AGP 9.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
