package com.kronokeep.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Asks for POST_NOTIFICATIONS once, when [shouldAsk] turns true (the first schedule was just created). */
@Composable
fun NotificationPermissionGate(shouldAsk: Boolean, onAsked: () -> Unit) {
    val context = LocalContext.current
    // The answer doesn't change what we do next: reminders just stay silent if it's denied.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(shouldAsk) {
        if (!shouldAsk) return@LaunchedEffect
        onAsked()
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
