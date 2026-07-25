// Top-level build file. Real per-module configuration lives in app/build.gradle.kts;
// this file only declares (without applying) the plugin versions shared across modules,
// so a future module (e.g. a Stage 1 pure-Kotlin `core` module) can pick them up without
// re-resolving versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
