package tv.mars.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import tv.mars.app.core.SourceType
import tv.mars.app.ui.components.ErrorBanner
import tv.mars.app.ui.components.FocusSurface
import tv.mars.app.ui.components.MarsButton
import tv.mars.app.ui.components.MarsLogo
import tv.mars.app.ui.theme.MarsMidnight
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsSurface
import tv.mars.app.ui.theme.MarsWhite

@Composable
fun AccountSetupScreen(
    privatePortalConfigured: Boolean,
    isConnecting: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onConnect: (SourceType, String, String, String, String, String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var method by rememberSaveable {
        mutableStateOf(if (privatePortalConfigured) SourceType.PRIVATE_XTREAM else SourceType.XTREAM)
    }
    var accountName by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    if (isConnecting) BackHandler(onBack = {})
    else if (onCancel != null) BackHandler(onBack = onCancel)

    val valid = when (method) {
        SourceType.PRIVATE_XTREAM -> privatePortalConfigured && username.isNotBlank() && password.isNotBlank()
        SourceType.XTREAM -> serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        SourceType.M3U -> m3uUrl.isNotBlank()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
        val wide = maxWidth >= 900.dp
        val contentModifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
        if (wide) {
            Row(
                modifier = contentModifier,
                horizontalArrangement = Arrangement.spacedBy(54.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandWelcome(modifier = Modifier.weight(0.85f))
                AccountForm(
                    method = method,
                    onMethodChange = { if (it != SourceType.PRIVATE_XTREAM || privatePortalConfigured) method = it },
                    privatePortalConfigured = privatePortalConfigured,
                    accountName = accountName,
                    onAccountNameChange = { accountName = it },
                    serverUrl = serverUrl,
                    onServerUrlChange = { serverUrl = it },
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                    m3uUrl = m3uUrl,
                    onM3uUrlChange = { m3uUrl = it },
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    valid = valid,
                    isConnecting = isConnecting,
                    errorMessage = errorMessage,
                    onClearError = onClearError,
                    onSubmit = { onConnect(method, accountName, serverUrl, username, password, m3uUrl) },
                    onCancel = onCancel,
                    modifier = Modifier.weight(1.15f),
                )
            }
        } else {
            Column(modifier = contentModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                BrandWelcome(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(28.dp))
                AccountForm(
                    method = method,
                    onMethodChange = { if (it != SourceType.PRIVATE_XTREAM || privatePortalConfigured) method = it },
                    privatePortalConfigured = privatePortalConfigured,
                    accountName = accountName,
                    onAccountNameChange = { accountName = it },
                    serverUrl = serverUrl,
                    onServerUrlChange = { serverUrl = it },
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                    m3uUrl = m3uUrl,
                    onM3uUrlChange = { m3uUrl = it },
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    valid = valid,
                    isConnecting = isConnecting,
                    errorMessage = errorMessage,
                    onClearError = onClearError,
                    onSubmit = { onConnect(method, accountName, serverUrl, username, password, m3uUrl) },
                    onCancel = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BrandWelcome(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
        MarsLogo()
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Your channels.\nOne orbit.",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = MarsWhite,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Connect an authorized IPTV subscription. MarsTV provides the player and keeps your account data on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MarsMuted,
        )
    }
}

@Composable
private fun AccountForm(
    method: SourceType,
    onMethodChange: (SourceType) -> Unit,
    privatePortalConfigured: Boolean,
    accountName: String,
    onAccountNameChange: (String) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    m3uUrl: String,
    onM3uUrlChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    valid: Boolean,
    isConnecting: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp)) {
        Text("Add a TV source", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text("Choose how this subscription signs in.", color = MarsMuted)
        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MethodCard(
                title = "MarsTV",
                icon = { Icon(Icons.Default.Key, null) },
                selected = method == SourceType.PRIVATE_XTREAM,
                enabled = privatePortalConfigured,
                onClick = { onMethodChange(SourceType.PRIVATE_XTREAM) },
                modifier = Modifier.weight(1f),
            )
            MethodCard(
                title = "Xtream",
                icon = { Icon(Icons.Default.Public, null) },
                selected = method == SourceType.XTREAM,
                enabled = true,
                onClick = { onMethodChange(SourceType.XTREAM) },
                modifier = Modifier.weight(1f),
            )
            MethodCard(
                title = "M3U",
                icon = { Icon(Icons.Default.Link, null) },
                selected = method == SourceType.M3U,
                enabled = true,
                onClick = { onMethodChange(SourceType.M3U) },
                modifier = Modifier.weight(1f),
            )
        }

        if (!privatePortalConfigured) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Username-only MarsTV login becomes available after MARSTV_PRIVATE_PORTAL_URL is set at build time.",
                style = MaterialTheme.typography.labelMedium,
                color = MarsMuted,
            )
        }

        Spacer(Modifier.height(18.dp))
        MarsTextField(
            value = accountName,
            onValueChange = onAccountNameChange,
            label = "Account name (optional)",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        if (method == SourceType.XTREAM) {
            MarsTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = "Server URL",
                placeholder = "http://provider.example:8080",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }

        if (method != SourceType.M3U) {
            MarsTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = "Username",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            MarsTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "Password",
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    Text(
                        text = if (showPassword) "Hide" else "Show",
                        color = MarsRed,
                        modifier = Modifier.clickable(onClick = onTogglePassword).padding(10.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            MarsTextField(
                value = m3uUrl,
                onValueChange = onM3uUrlChange,
                label = "Full M3U playlist URL",
                placeholder = "http://provider.example/get.php?username=…",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(14.dp))
        ErrorBanner(message = errorMessage, onDismiss = onClearError)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (!isConnecting && onCancel != null) {
                MarsButton(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(0.55f))
            }
            MarsButton(
                text = if (isConnecting) "Checking source…" else "Connect account",
                onClick = onSubmit,
                enabled = valid && !isConnecting,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MethodCard(
    title: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(82.dp),
        selected = selected,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box { icon() }
            Spacer(Modifier.height(5.dp))
            Text(title, fontWeight = FontWeight.Bold, color = if (enabled) MarsWhite else MarsMuted)
        }
    }
}

@Composable
private fun MarsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder, color = MarsMuted) },
        modifier = modifier,
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MarsRed,
            unfocusedBorderColor = MarsSurface,
            focusedLabelColor = MarsRed,
            cursorColor = MarsRed,
        ),
    )
}
