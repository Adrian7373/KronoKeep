package com.kronokeep.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.ui.components.brushedMetal
import com.kronokeep.app.ui.components.drawRivet
import com.kronokeep.app.ui.theme.PlexMono
import com.kronokeep.app.ui.theme.KronoTheme

/** The app mark: a small riveted asset tag with an icon stamped in teal. */
@Composable
private fun BrandTag(icon: ImageVector = Icons.Outlined.Lock) {
    val metal = KronoTheme.metal
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        border = BorderStroke(1.dp, metal.edge),
        shadowElevation = 2.dp,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .background(brushedMetal(metal.face))
                .drawBehind {
                    val r = 4.dp.toPx()
                    val inset = 12.dp.toPx()
                    listOf(
                        Offset(inset, inset),
                        Offset(size.width - inset, inset),
                        Offset(inset, size.height - inset),
                        Offset(size.width - inset, size.height - inset),
                    ).forEach { drawRivet(metal, it, r) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun AuthScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resetting = state.mode != AuthMode.SIGN_IN && state.mode != AuthMode.SIGN_UP

    // System back steps out of the reset flow instead of closing the app.
    BackHandler(enabled = resetting) {
        when (state.mode) {
            AuthMode.RESET_CODE -> viewModel.changeEmail()
            AuthMode.NEW_PASSWORD -> viewModel.cancelPasswordReset()
            else -> viewModel.backToSignIn()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandTag(if (resetting) Icons.Outlined.LockReset else Icons.Outlined.Lock)
        val (title, subtitle) = when (state.mode) {
            AuthMode.SIGN_IN, AuthMode.SIGN_UP -> "KronoKeep" to "Warranties, receipts and maintenance, all in one vault."
            AuthMode.RESET_REQUEST -> "Reset password" to "Enter your account's email and we'll send you a code."
            AuthMode.RESET_CODE -> "Enter the code" to "Type the code from the email we sent to ${state.email.trim()}."
            AuthMode.NEW_PASSWORD -> "New password" to "Choose a new password for ${state.email.trim()}."
        }
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))

        when (state.mode) {
            AuthMode.SIGN_IN, AuthMode.SIGN_UP -> {
                EmailField(state.email, viewModel::onEmailChange, ImeAction.Next) {}
                PasswordField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = "Password",
                    supportingText = if (state.isSignUp) "At least ${AuthValidation.MIN_PASSWORD_LENGTH} characters" else null,
                    imeAction = ImeAction.Done,
                    onDone = viewModel::submit,
                    newPassword = state.isSignUp,
                )
                if (!state.isSignUp) {
                    TextButton(onClick = viewModel::startPasswordReset, modifier = Modifier.align(Alignment.End)) {
                        Text("Forgot password?")
                    }
                }
            }
            AuthMode.RESET_REQUEST -> EmailField(state.email, viewModel::onEmailChange, ImeAction.Done, viewModel::submit)
            AuthMode.RESET_CODE -> OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::onCodeChange,
                label = { Text("Code") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = PlexMono, letterSpacing = 4.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
            )
            AuthMode.NEW_PASSWORD -> {
                PasswordField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = "New password",
                    supportingText = "At least ${AuthValidation.MIN_PASSWORD_LENGTH} characters",
                    imeAction = ImeAction.Next,
                    onDone = {},
                    newPassword = true,
                )
                PasswordField(
                    value = state.confirmPassword,
                    onValueChange = viewModel::onConfirmPasswordChange,
                    label = "Confirm new password",
                    supportingText = if (state.passwordsMismatch) "Passwords don't match" else null,
                    isError = state.passwordsMismatch,
                    imeAction = ImeAction.Done,
                    onDone = viewModel::submit,
                    newPassword = true,
                )
            }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        state.info?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }

        Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (state.isBusy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    when (state.mode) {
                        AuthMode.SIGN_IN -> "Sign in"
                        AuthMode.SIGN_UP -> "Create account"
                        AuthMode.RESET_REQUEST -> "Send code"
                        AuthMode.RESET_CODE -> "Verify code"
                        AuthMode.NEW_PASSWORD -> "Save password"
                    },
                )
            }
        }

        when (state.mode) {
            AuthMode.SIGN_IN, AuthMode.SIGN_UP -> TextButton(onClick = viewModel::toggleMode) {
                Text(if (state.isSignUp) "Have an account? Sign in" else "New here? Create an account")
            }
            AuthMode.RESET_REQUEST -> TextButton(onClick = viewModel::backToSignIn) { Text("Back to sign in") }
            AuthMode.RESET_CODE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::resendCode, enabled = !state.isBusy) { Text("Send a new code") }
                TextButton(onClick = viewModel::changeEmail, enabled = !state.isBusy) { Text("Use another email") }
            }
            AuthMode.NEW_PASSWORD -> TextButton(onClick = viewModel::cancelPasswordReset, enabled = !state.isBusy) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun EmailField(value: String, onValueChange: (String) -> Unit, imeAction: ImeAction, onDone: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String?,
    imeAction: ImeAction,
    onDone: () -> Unit,
    isError: Boolean = false,
    newPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        // NewPassword lets the password manager offer to save the new one.
        modifier = Modifier.fillMaxWidth().semantics {
            contentType = if (newPassword) ContentType.NewPassword else ContentType.Password
        },
    )
}
