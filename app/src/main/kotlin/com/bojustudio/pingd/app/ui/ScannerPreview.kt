package com.bojustudio.pingd.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.bojustudio.pingd.app.state.PingdIconDisplay

const val TAG_SCANNER_PREVIEW = "settings_scanner_preview"

private const val SCANNER_PREVIEW_WIDTH_FRACTION = 0.25f

/**
 * A live, in-app duplicate of the status-bar tracer icon: the exact drawable resource
 * [com.bojustudio.pingd.app.service.PingdNotificationController] is showing in the notification
 * right now, read straight from [PingdIconDisplay] — not a separate rendering, and not a
 * second interpretation of the tracer's state derived some other way.
 *
 * Renders nothing at all while [PingdIconDisplay.iconRes] is `null`. Per spec, `HIDDEN` is
 * the absence of the icon, not a seventh frame, and this preview has no more business showing
 * something in that case than the status bar does — so the composable itself has no node in
 * the tree, rather than an invisible or empty one.
 */
@Composable
fun ScannerPreview(modifier: Modifier = Modifier) {
    val iconRes by PingdIconDisplay.iconRes.collectAsState()
    val currentIconRes = iconRes ?: return

    Box(
        modifier = modifier.fillMaxWidth().testTag(TAG_SCANNER_PREVIEW),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = currentIconRes),
            // Decorative duplicate of the status-bar icon -- the settings screen's status
            // line already gives a screen reader an accessible readout of the same
            // underlying state (see SettingsScreen's "Status: ..." text).
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(SCANNER_PREVIEW_WIDTH_FRACTION)
                .aspectRatio(1f),
        )
    }
}
