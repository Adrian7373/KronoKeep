package com.kronokeep.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.BuildConfig
import com.kronokeep.app.domain.auth.SignOutUseCase
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.domain.repository.SyncRepository
import com.kronokeep.app.domain.repository.SyncStatus
import com.kronokeep.app.domain.settings.ThemeMode
import com.kronokeep.app.ui.components.IconBadge
import com.kronokeep.app.ui.components.KronoCard
import com.kronokeep.app.ui.components.KronoDefaults
import com.kronokeep.app.ui.components.KronoTopBar
import com.kronokeep.app.ui.components.SectionLabel
import com.kronokeep.app.ui.theme.KronoTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val signOutUseCase: SignOutUseCase,
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state

    val syncStatus: StateFlow<SyncStatus?> = syncRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() = syncRepository.syncNow()

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun signOut() {
        viewModelScope.launch { signOutUseCase() }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    val remindersEnabled: StateFlow<Boolean> = settingsRepository.remindersEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRemindersEnabled(enabled) }
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }

private val ThemeMode.caption: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "Follows your phone's dark theme setting."
        ThemeMode.LIGHT -> "Brushed aluminium, always."
        ThemeMode.DARK -> "Anodised gunmetal, always."
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val remindersOn by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-checked on resume: the user may have just changed it in system settings.
    var systemAllows by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        systemAllows = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    Scaffold(topBar = { KronoTopBar(title = "Settings", actions = { ThemeToggleButton() }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionLabel("Account")
            SettingsGroup {
                when (val a = auth) {
                    is AuthState.SignedIn -> {
                        SettingsRow(
                            icon = Icons.Outlined.AccountCircle,
                            title = a.email ?: "Signed in",
                            body = "Your vault syncs to the cloud",
                        )
                        syncStatus?.let {
                            GroupDivider()
                            SyncRow(it, onSyncNow = viewModel::syncNow)
                        }
                        GroupDivider()
                        SettingsRow(
                            icon = Icons.AutoMirrored.Outlined.Logout,
                            title = "Sign out",
                            onClick = viewModel::signOut,
                        )
                    }
                    else -> SettingsRow(
                        icon = Icons.Outlined.CloudOff,
                        title = "Cloud not configured",
                        body = "Add supabase.url and supabase.publishableKey to local.properties",
                    )
                }
            }

            SectionLabel("Appearance")
            SettingsGroup {
                SettingsRow(icon = Icons.Outlined.Contrast, title = "Theme", body = themeMode.caption)
                ThemeModePicker(
                    selected = themeMode,
                    onSelect = viewModel::setThemeMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 72.dp, end = 16.dp, bottom = 16.dp),
                )
            }

            SectionLabel("Notifications")
            SettingsGroup {
                SettingsRow(
                    icon = Icons.Outlined.NotificationsActive,
                    title = "Maintenance reminders",
                    body = if (remindersOn && !systemAllows) {
                        "Blocked in system settings. Tap to allow."
                    } else {
                        "Around 8 AM when something is due soon or overdue. Works offline."
                    },
                    bodyColor = if (remindersOn && !systemAllows) KronoTheme.status.overdue else null,
                    trailing = {
                        Switch(
                            checked = remindersOn,
                            onCheckedChange = viewModel::setRemindersEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    },
                    onClick = {
                        if (remindersOn && !systemAllows) {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } else {
                            viewModel.setRemindersEnabled(!remindersOn)
                        }
                    },
                )
            }

            SectionLabel("About")
            SettingsGroup {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "About KronoKeep",
                    body = "Version ${BuildConfig.VERSION_NAME}",
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    KronoCard(modifier = Modifier.padding(horizontal = 16.dp), contentPadding = 0.dp, content = content)
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    body: String? = null,
    iconTint: Color? = null,
    bodyColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = body?.let { { Text(it, color = bodyColor ?: MaterialTheme.colorScheme.onSurfaceVariant) } },
        leadingContent = {
            IconBadge(
                icon,
                content = iconTint ?: MaterialTheme.colorScheme.onPrimaryContainer,
                size = 36.dp,
            )
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun SyncRow(status: SyncStatus, onSyncNow: () -> Unit) {
    val (icon, tint) = when {
        status.rejected > 0 -> Icons.Outlined.ErrorOutline to KronoTheme.status.overdue
        status.allSynced -> Icons.Outlined.CloudDone to KronoTheme.status.ok
        else -> Icons.Outlined.CloudSync to null
    }
    SettingsRow(
        icon = icon,
        iconTint = tint,
        title = "Sync",
        body = status.summary(),
        trailing = {
            if (status.running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onSyncNow) { Text("Sync now") }
            }
        },
    )
}

@Composable
private fun ThemeModePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                colors = KronoDefaults.segmentedButtonColors(),
                icon = {},
                label = { Text(mode.label) },
            )
        }
    }
}
