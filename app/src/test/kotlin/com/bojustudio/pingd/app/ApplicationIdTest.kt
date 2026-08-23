package com.bojustudio.pingd.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A genuine "would fail if the setup were broken" check on the Gradle/Kotlin
 * scaffold itself: confirms the applicationId and versionName declared in
 * app/build.gradle.kts actually make it through Gradle's generated BuildConfig
 * into compiled JVM unit-test code. This fails if BuildConfig generation is
 * disabled, if defaultConfig drifts from what's declared here, or if the test
 * source set loses visibility into the app's generated code.
 */
class ApplicationIdTest {

    @Test
    fun applicationIdMatchesDeclaredPlaceholderPackage() {
        assertEquals("com.bojustudio.pingd.app", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun versionNameMatchesDefaultConfig() {
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
    }
}
