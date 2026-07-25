package com.uplinkstatus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Stage 0 placeholder screen. Its only job is to prove Jetpack Compose is wired
 * up end to end (dependencies resolve, the Compose compiler plugin runs, the
 * activity renders it). Stage 3 replaces this with the real settings screen
 * (master toggle, network scope, SSID whitelist, ping target) — do not build
 * that logic here.
 */
const val PLACEHOLDER_SCREEN_TEXT = "UplinkStatus scaffold is running"

@Composable
fun PlaceholderScreen(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = PLACEHOLDER_SCREEN_TEXT)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    MaterialTheme {
        PlaceholderScreen()
    }
}
