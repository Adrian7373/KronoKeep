package com.kronokeep.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.BuildConfig
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state
    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.padding(padding)) {
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
            ListItem(
                headlineContent = { Text("About KronoKeep") },
                supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            )
        }
    }
}
