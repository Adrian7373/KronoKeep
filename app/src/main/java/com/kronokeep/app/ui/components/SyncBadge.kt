package com.kronokeep.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.ui.theme.KronoTheme

/** "Saved on phone" vs "Synced": honest about where the latest change lives. */
@Composable
fun SyncBadge(state: SyncState, modifier: Modifier = Modifier, pendingLabel: String = "Saved on phone") {
    val (icon, label, tint) = when (state) {
        SyncState.SYNCED -> Triple(Icons.Outlined.CloudDone, "Synced", KronoTheme.status.ok)
        SyncState.PENDING -> Triple(Icons.Outlined.PhoneAndroid, pendingLabel, MaterialTheme.colorScheme.onSurfaceVariant)
        SyncState.FAILED -> Triple(Icons.Outlined.ErrorOutline, "Sync failed", KronoTheme.status.overdue)
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
