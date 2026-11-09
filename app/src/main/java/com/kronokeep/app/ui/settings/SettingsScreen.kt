package com.kronokeep.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.BuildConfig
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.domain.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val remindersOn by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-checked on resume: the user may have just changed it in system settings.
    var systemAllows by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        systemAllows = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Account")
            when (val a = auth) {
                is AuthState.SignedIn -> {
                    ListItem(
                        headlineContent = { Text(a.email ?: "Signed in") },
                        supportingContent = { Text("Your vault syncs to the cloud") },
                        leadingContent = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                    )
                    ListItem(
                        headlineContent = { Text("Sign out") },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = viewModel::signOut),
                    )
                }
                else -> ListItem(
                    headlineContent = { Text("Cloud not configured") },
                    supportingContent = { Text("Add supabase.url and supabase.publishableKey to local.properties") },
                    leadingContent = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
                )
            }

            SectionHeader("Appearance")
            ListItem(
                headlineContent = { Text("Theme") },
                supportingContent = { Text("System follows your phone's dark theme setting") },
                leadingContent = { Icon(Icons.Outlined.Contrast, contentDescription = null) },
            )
            ThemeModePicker(
                selected = themeMode,
                onSelect = viewModel::setThemeMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            )

            SectionHeader("Notifications")
            ListItem(
                headlineContent = { Text("Maintenance reminders") },
                supportingContent = {
                    Text(
                        if (remindersOn && !systemAllows) {
                            "Blocked in system settings. Tap to allow."
                        } else {
                            "Around 8 AM when something is due soon or overdue. Works offline."
                        },
                    )
                },
                leadingContent = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null) },
                trailingContent = {
                    Switch(checked = remindersOn, onCheckedChange = viewModel::setRemindersEnabled)
                },
                modifier = Modifier.clickable {
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

            SectionHeader("About")
            ListItem(
                headlineContent = { Text("About KronoKeep") },
                supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
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
                label = { Text(mode.label) },
            )
        }
    }
}
