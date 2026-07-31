// Stage 1's home: the probe and tracer/ack state-machine logic, as plain Kotlin with zero
// Android framework dependency (per the spec's Implementation Baseline and the brief's
// platform baseline). Using the `kotlin.jvm` plugin instead of `kotlin.android` is what
// enforces that boundary at build time — this module physically cannot depend on
// android.* classes, so there's no way for Android-framework logic to creep in here later.
// It runs as ordinary JVM unit tests (no Robolectric, no instrumentation needed).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Match app/build.gradle.kts's JVM target (17) without requiring an explicit Gradle
// toolchain download — this environment only has a JDK 21 installed, and `jvmToolchain(17)`
// would force Gradle to provision one. Targeting bytecode 17 from whatever JDK is present
// (21 here) works the same way app/build.gradle.kts's `kotlinOptions.jvmTarget = "17"` does.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
