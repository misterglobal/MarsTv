package tv.mars.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.mars.app.core.Category
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.ViewerProfile
import tv.mars.app.ui.components.FocusSurface
import tv.mars.app.ui.theme.MarsMidnight
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsSurface
import tv.mars.app.ui.theme.MarsSurfaceRaised
import tv.mars.app.ui.theme.MarsViolet
import tv.mars.app.ui.theme.MarsWhite

@Composable
fun SettingsScreen(
    accounts: List<IptvAccount>,
    activeAccountId: String?,
    profiles: List<ViewerProfile>,
    activeProfile: ViewerProfile?,
    categories: List<Category>,
    isPro: Boolean,
    pinMatches: (String) -> Boolean,
    onSelectAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRefresh: () -> Unit,
    onOpenProfiles: () -> Unit,
    onSetPin: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pinDialogMode by remember { mutableStateOf<PinDialogMode?>(null) }
    var parentalUnlocked by remember(activeProfile?.id) { mutableStateOf(activeProfile?.pinHash.isNullOrBlank()) }
    val restricted = activeProfile?.restrictedCategoryKeys.orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("Accounts, profiles, and parental controls", color = MarsMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
        }

        if (!isPro) {
            item {
                FocusSurface(onClick = onUpgrade) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VpnKey, null, tint = MarsRed)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Upgrade to MarsTV Pro", fontWeight = FontWeight.Bold)
                            Text("Lifetime access for one device · CAD $14.99", color = MarsMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("View", color = MarsRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item { SettingsHeading("TV sources") }
        items(accounts, key = IptvAccount::id) { account ->
            SettingsRow(
                icon = { Icon(Icons.Default.Tv, null) },
                title = account.name,
                subtitle = "${account.sourceLabel}  •  ${account.safeHost}",
                selected = account.id == activeAccountId,
                onClick = { onSelectAccount(account.id) },
                trailing = {
                    IconButton(onClick = { onRemoveAccount(account.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove account", tint = MarsMuted)
                    }
                },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusSurface(onClick = onAddAccount) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = MarsRed)
                        Spacer(Modifier.width(6.dp))
                        Text("Add source", fontWeight = FontWeight.Bold)
                    }
                }
                FocusSurface(onClick = onRefresh) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, null, tint = MarsMuted)
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh lineup")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        item { SettingsHeading("Profiles") }
        item {
            SettingsRow(
                icon = { Avatar(activeProfile?.avatarIndex ?: 0, 42) },
                title = activeProfile?.name ?: "Main",
                subtitle = "${profiles.size} profile${if (profiles.size == 1) "" else "s"} on this device",
                selected = true,
                onClick = onOpenProfiles,
                trailing = { Text("Manage", color = MarsRed, fontWeight = FontWeight.Bold) },
            )
            Spacer(Modifier.height(10.dp))
        }

        item { SettingsHeading("Parental controls") }
        item {
            val hasPin = !activeProfile?.pinHash.isNullOrBlank()
            SettingsRow(
                icon = { Icon(if (hasPin) Icons.Default.VpnKey else Icons.Default.Lock, null) },
                title = if (hasPin) "Profile PIN is set" else "Set a profile PIN",
                subtitle = if (hasPin) "Required to open selected categories" else "Create a PIN before selecting restricted categories",
                selected = false,
                onClick = {
                    pinDialogMode = if (hasPin) PinDialogMode.CHANGE else PinDialogMode.CREATE
                },
                trailing = {
                    Text(if (hasPin) "Change" else "Set PIN", color = MarsRed, fontWeight = FontWeight.Bold)
                },
            )
        }

        if (!activeProfile?.pinHash.isNullOrBlank() && !parentalUnlocked) {
            item {
                FocusSurface(onClick = { pinDialogMode = PinDialogMode.UNLOCK }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = MarsViolet)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Unlock category settings", fontWeight = FontWeight.Bold)
                            Text("Enter the current PIN before changing restrictions", color = MarsMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            items(categories.distinctBy(Category::key), key = Category::key) { category ->
                SettingsRow(
                    icon = {
                        if (category.key in restricted) Icon(Icons.Default.Lock, null, tint = MarsRed)
                        else Icon(Icons.Default.Check, null, tint = MarsMuted)
                    },
                    title = category.name,
                    subtitle = category.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = category.key in restricted,
                    onClick = {
                        if (activeProfile?.pinHash.isNullOrBlank()) pinDialogMode = PinDialogMode.CREATE
                        else onToggleCategory(category.key)
                    },
                    trailing = {
                        Switch(
                            checked = category.key in restricted,
                            onCheckedChange = {
                                if (activeProfile?.pinHash.isNullOrBlank()) pinDialogMode = PinDialogMode.CREATE
                                else onToggleCategory(category.key)
                            },
                        )
                    },
                )
            }
        }
        item { Spacer(Modifier.height(34.dp)) }
    }

    pinDialogMode?.let { mode ->
        PinManagementDialog(
            mode = mode,
            hasExistingPin = !activeProfile?.pinHash.isNullOrBlank(),
            pinMatches = pinMatches,
            onDismiss = { pinDialogMode = null },
            onSave = { pin ->
                when (mode) {
                    PinDialogMode.UNLOCK -> parentalUnlocked = true
                    PinDialogMode.CREATE, PinDialogMode.CHANGE -> {
                        onSetPin(pin)
                        parentalUnlocked = true
                    }
                }
                pinDialogMode = null
            },
        )
    }
}

private enum class PinDialogMode { CREATE, CHANGE, UNLOCK }

@Composable
private fun PinManagementDialog(
    mode: PinDialogMode,
    hasExistingPin: Boolean,
    pinMatches: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val changing = mode == PinDialogMode.CHANGE
    val unlocking = mode == PinDialogMode.UNLOCK

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when (mode) {
            PinDialogMode.CREATE -> "Create profile PIN"
            PinDialogMode.CHANGE -> "Change profile PIN"
            PinDialogMode.UNLOCK -> "Unlock parental settings"
        }) },
        text = {
            Column {
                if (hasExistingPin) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { currentPin = it.filter(Char::isDigit).take(8); error = "" },
                        label = { Text("Current PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
                if (!unlocking) {
                    if (hasExistingPin) Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter(Char::isDigit).take(8); error = "" },
                        label = { Text("New PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it.filter(Char::isDigit).take(8); error = "" },
                        label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                error = when {
                    hasExistingPin && !pinMatches(currentPin) -> "Current PIN is incorrect"
                    !unlocking && newPin.length < 4 -> "Use at least four digits"
                    !unlocking && newPin != confirmation -> "The new PINs do not match"
                    else -> ""
                }
                if (error.isBlank()) onSave(if (unlocking) currentPin else newPin)
            }) { Text(if (unlocking) "Unlock" else "Save PIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProfilesScreen(
    profiles: List<ViewerProfile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var showAdd by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().background(MarsMidnight).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Who’s watching?", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            profiles.forEach { profile ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusSurface(
                        onClick = { onSelect(profile.id) },
                        selected = profile.id == activeProfileId,
                        modifier = Modifier.size(148.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Avatar(profile.avatarIndex, 74)
                            Spacer(Modifier.height(10.dp))
                            Text(profile.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (profiles.size > 1) {
                        IconButton(onClick = { onRemove(profile.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete profile", tint = MarsMuted)
                        }
                    }
                }
            }
            FocusSurface(onClick = { showAdd = true }, modifier = Modifier.size(148.dp)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(46.dp), tint = MarsRed)
                    Text("Add profile", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add profile") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("Profile name") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { if (name.isNotBlank()) { onAdd(name); showAdd = false } }, enabled = name.isNotBlank()) {
                    Text("Create")
                }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    FocusSurface(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MarsMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            trailing()
        }
    }
}

@Composable
private fun Avatar(index: Int, size: Int) {
    val colors = listOf(MarsRed, MarsViolet, Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEC4899))
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(colors[(index % colors.size + colors.size) % colors.size]),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.58f).dp))
    }
}
