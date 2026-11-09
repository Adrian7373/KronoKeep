package com.kronokeep.app.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kronokeep.app.ui.components.CloudNotConfiguredBanner
import com.kronokeep.app.ui.components.EmptyState

/** Milestone 3 fills this with overdue / due soon / expiring warranties. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(cloudConfigured: Boolean) {
    Scaffold(topBar = { TopAppBar(title = { Text("Dashboard") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            if (!cloudConfigured) CloudNotConfiguredBanner()
            EmptyState(
                icon = Icons.Outlined.EventAvailable,
                title = "Nothing due yet",
                body = "Add a vehicle or appliance and KronoKeep will tell you when maintenance is coming up.",
            )
        }
    }
}
