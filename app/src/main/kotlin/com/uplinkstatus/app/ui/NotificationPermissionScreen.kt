package com.uplinkstatus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.uplinkstatus.app.R

/**
 * Stage 2's `POST_NOTIFICATIONS` rationale + request flow. Shown instead of
 * [PlaceholderScreen] whenever the app doesn't (yet) have notification permission —
 * without it, the status-bar icon (this app's entire reason for existing) can't be shown
 * at all, so the spec requires a real request/rationale flow rather than a bare manifest
 * entry. Deliberately minimal: this is plumbing for a required runtime permission, not the
 * real settings screen Stage 3 builds — no master toggle, network scope, or other Stage 3
 * preference lives here.
 */
@Composable
fun NotificationPermissionScreen(
    showDenied: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(R.string.notification_permission_rationale))
            if (showDenied) {
                Text(text = stringResource(R.string.notification_permission_denied))
            }
            Button(onClick = onRequestPermission) {
                Text(text = stringResource(R.string.notification_permission_request_button))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NotificationPermissionScreenPreview() {
    MaterialTheme {
        NotificationPermissionScreen(showDenied = false, onRequestPermission = {})
    }
}
