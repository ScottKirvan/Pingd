package com.uplinkstatus.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Wraps the app in whatever the system's actual theme is — light/dark following
 * [isSystemInDarkTheme], and Material You dynamic color (the user's wallpaper-derived
 * palette) rather than a fixed brand color. `minSdk` here is 34, well past the API 31 dynamic
 * color was introduced in, so there's no need for a static-palette fallback branch for older
 * devices the way a lower-`minSdk` app would need.
 */
@Composable
fun UplinkStatusTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
