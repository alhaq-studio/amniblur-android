package com.amngaze

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * 1. Onboarding & Settings Protection Mode Selector Screen (`ProtectionModeScreen.kt`).
 * Presents Card 1 (`In-app protection`) and Card 2 (`System-wide protection`) with a bottom `Save` CTA.
 */
@Composable
fun ProtectionModeScreen(
    initialMode: AppProtectionMode,
    onSaveMode: (AppProtectionMode) -> Unit,
    onBack: (() -> Unit)? = null
) {
    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(20.dp)
            .testTag("protection_mode_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("protection_mode_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Protection Mode",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Choose how AmnGaze protects your browsing and apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Card 1: In-app protection
        ProtectionModeOptionCard(
            title = "In-app protection",
            description = "Protect content only inside AmnGaze's built-in browser and connected web views.",
            bullets = listOf(
                "Fast setup",
                "Works inside AmnGaze only",
                "No permissions needed"
            ),
            icon = Icons.Default.Public,
            selected = selectedMode == AppProtectionMode.IN_APP_PROTECTION,
            onClick = { selectedMode = AppProtectionMode.IN_APP_PROTECTION },
            testTag = "mode_card_in_app"
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Card 2: System-wide protection
        ProtectionModeOptionCard(
            title = "System-wide protection",
            description = "Protect from sensitive content across supported apps and browsers.",
            bullets = listOf(
                "Works beyond AmnGaze",
                "Better for social apps",
                "Requires system permission"
            ),
            icon = Icons.Default.Shield,
            selected = selectedMode == AppProtectionMode.SYSTEM_WIDE_PROTECTION,
            onClick = { selectedMode = AppProtectionMode.SYSTEM_WIDE_PROTECTION },
            testTag = "mode_card_system_wide"
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { onSaveMode(selectedMode) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("protection_mode_save_button"),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "Save",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProtectionModeOptionCard(
    title: String,
    description: String,
    bullets: List<String>,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag(testTag),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                RadioButton(
                    selected = selected,
                    onClick = onClick
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            bullets.forEach { bullet ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "- ",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = bullet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 2. Dynamic Mode-Aware Settings Screen (`SettingsScreen.kt`).
 * Adapts its rows dynamically depending on whether `appProtectionMode` is `SYSTEM_WIDE_PROTECTION`
 * or `IN_APP_PROTECTION`, and enforces the 4-digit Parental Control PIN gate when enabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AmnGazeState,
    viewModel: AmnGazeViewModel,
    onOpenProtectionModeChooser: () -> Unit,
    onOpenConnectedPlatforms: () -> Unit,
    onOpenSystemWideDashboard: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showWhoToBlurDialog by remember { mutableStateOf(false) }
    var showSensitivityDialog by remember { mutableStateOf(false) }
    var showBlurStyleDialog by remember { mutableStateOf(false) }
    var showWhatToProtectDialog by remember { mutableStateOf(false) }
    var showFeedControlDialog by remember { mutableStateOf(false) }
    var showUsageLimitsDialog by remember { mutableStateOf(false) }
    var showParentalDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var pendingPinProtectedAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val runWithPinGate: (() -> Unit) -> Unit = { action ->
        if (state.parentalPinEnabled) {
            pendingPinProtectedAction = action
        } else {
            action()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .testTag("mode_aware_settings_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Text(
                    text = "PERSONALIZATION",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }

            // Row 1: Protection Mode
            item {
                SettingsRowCard(
                    icon = Icons.Default.Shield,
                    title = "Protection Mode",
                    value = state.appProtectionMode.title,
                    onClick = { runWithPinGate { onOpenProtectionModeChooser() } },
                    testTag = "settings_row_protection_mode"
                )
            }

            // Row 2: Who to Blur
            item {
                SettingsRowCard(
                    icon = Icons.Default.Face,
                    title = "Who to Blur",
                    value = state.haramBlurTargetGender.displayName,
                    onClick = { runWithPinGate { showWhoToBlurDialog = true } },
                    testTag = "settings_row_who_to_blur"
                )
            }

            if (state.appProtectionMode == AppProtectionMode.SYSTEM_WIDE_PROTECTION) {
                // System-Wide Mode Rows
                item {
                    SettingsRowCard(
                        icon = Icons.Default.Tune,
                        title = "Detection Sensitivity",
                        value = state.haramBlurSensitivity.shortLabel,
                        onClick = { runWithPinGate { showSensitivityDialog = true } },
                        testTag = "settings_row_sensitivity"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Dashboard,
                        title = "System-Wide Blocker & Shield Dashboard",
                        value = "Master Toggles 1 & 2",
                        onClick = onOpenSystemWideDashboard,
                        testTag = "settings_row_system_dashboard"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Lock,
                        title = "Parental Controls",
                        value = if (state.parentalPinEnabled) "PIN Lock Active" else "Off",
                        onClick = { showParentalDialog = true },
                        testTag = "settings_row_parental_controls"
                    )
                }
            } else {
                // In-App Protection Mode Rows
                item {
                    SettingsRowCard(
                        icon = Icons.Default.BlurOn,
                        title = "Blur Style",
                        value = state.blurStyleSummary,
                        onClick = { runWithPinGate { showBlurStyleDialog = true } },
                        testTag = "settings_row_blur_style"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Tune,
                        title = "Detection Sensitivity",
                        value = state.haramBlurSensitivity.shortLabel,
                        onClick = { runWithPinGate { showSensitivityDialog = true } },
                        testTag = "settings_row_sensitivity"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.VerifiedUser,
                        title = "What to Protect",
                        value = state.whatToProtectSummary,
                        onClick = { runWithPinGate { showWhatToProtectDialog = true } },
                        testTag = "settings_row_what_to_protect"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Apps,
                        title = "Connected Platforms",
                        value = state.enabledPlatformsSummary,
                        onClick = { runWithPinGate { onOpenConnectedPlatforms() } },
                        testTag = "settings_row_connected_platforms"
                    )
                }

                item {
                    val activeFeeds = listOfNotNull(
                        if (state.feedControlConfig.hideYoutubeShorts) "No Shorts" else null,
                        if (state.feedControlConfig.hideInstagramReels) "No Reels" else null,
                        if (state.feedControlConfig.hideAlgorithmicFeeds) "No For You" else null,
                        if (state.feedControlConfig.hideComments) "No Comments" else null
                    ).joinToString(", ").ifEmpty { "All Allowed" }

                    SettingsRowCard(
                        icon = Icons.Default.FilterList,
                        title = "Feed Control",
                        value = activeFeeds,
                        onClick = { runWithPinGate { showFeedControlDialog = true } },
                        testTag = "settings_row_feed_control"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Lock,
                        title = "Parental Controls",
                        value = if (state.parentalPinEnabled) "PIN Lock Active" else "Off",
                        onClick = { showParentalDialog = true },
                        testTag = "settings_row_parental_controls"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Timer,
                        title = "Usage & Time Limits",
                        value = "Daily platform quotas",
                        onClick = { runWithPinGate { showUsageLimitsDialog = true } },
                        testTag = "settings_row_usage_limits"
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "MORE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Link,
                        title = "Default App Links",
                        value = "Configure which links open with AmnGaze",
                        onClick = { openDefaultAppLinkSettings(context) },
                        testTag = "settings_row_default_links"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Language,
                        title = "Language",
                        value = state.selectedLanguage,
                        onClick = {
                            Toast.makeText(context, "Language: English", Toast.LENGTH_SHORT).show()
                        },
                        testTag = "settings_row_language"
                    )
                }

                item {
                    SettingsRowCard(
                        icon = Icons.Default.Info,
                        title = "About",
                        value = "v2.0 | 100% On-Device AI Privacy",
                        onClick = { showAboutDialog = true },
                        testTag = "settings_row_about"
                    )
                }
            }
        }
    }

    if (showWhoToBlurDialog) {
        WhoToBlurSelectorDialog(
            currentGender = state.haramBlurTargetGender,
            onSelectGender = {
                viewModel.updateHaramBlurTargetGender(it)
                showWhoToBlurDialog = false
            },
            onDismiss = { showWhoToBlurDialog = false }
        )
    }

    if (showSensitivityDialog) {
        DetectionSensitivitySelectorDialog(
            currentSensitivity = state.haramBlurSensitivity,
            onSelectSensitivity = {
                viewModel.updateHaramBlurSensitivity(it)
                showSensitivityDialog = false
            },
            onDismiss = { showSensitivityDialog = false }
        )
    }

    if (showBlurStyleDialog) {
        BlurStyleSheetDialog(
            currentStyle = state.haramBlurOverlayStyle,
            currentStrengthPx = state.inAppBlurStrengthPx,
            preBlurNewMedia = state.preBlurNewMediaUntilScanned,
            onUpdateStyle = { viewModel.updateHaramBlurOverlayStyle(it) },
            onUpdateStrengthPx = { viewModel.updateInAppBlurStrengthPx(it) },
            onTogglePreBlurNewMedia = { viewModel.setPreBlurNewMediaUntilScanned(it) },
            onDismiss = { showBlurStyleDialog = false }
        )
    }

    if (showWhatToProtectDialog) {
        WhatToProtectSelectorDialog(
            selectedItems = state.whatToProtect,
            onToggleItem = { viewModel.toggleWhatToProtectItem(it) },
            onDismiss = { showWhatToProtectDialog = false }
        )
    }

    if (showFeedControlDialog) {
        FeedControlDialog(
            config = state.feedControlConfig,
            onUpdateConfig = { viewModel.updateFeedControl(it) },
            onDismiss = { showFeedControlDialog = false }
        )
    }

    if (showUsageLimitsDialog) {
        UsageTimeLimitsDialog(
            platforms = state.connectedPlatforms,
            usageSecondsMap = state.platformDailyUsageSeconds,
            onUpdateLimit = { id, mins -> viewModel.updatePlatformDailyLimit(id, mins) },
            onResetUsage = { id -> viewModel.resetDailyUsageForPlatform(id) },
            onDismiss = { showUsageLimitsDialog = false }
        )
    }

    if (showParentalDialog) {
        ParentalControlsDialog(
            isPinEnabled = state.parentalPinEnabled,
            onEnablePin = { pin -> viewModel.enableParentalPin(pin) },
            onDisablePin = { pin -> viewModel.disableParentalPin(pin) },
            onDismiss = { showParentalDialog = false }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About AmnGaze v2.0", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "AmnGaze runs 100% on-device computer vision (LiteRT MobileNetV2 & Gender/Face Filters). " +
                        "No screenshots, browsing history, or images ever leave your phone."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    val pendingAction = pendingPinProtectedAction
    if (pendingAction != null) {
        ParentalPinPromptDialog(
            onVerifyPin = { entered -> viewModel.verifyParentalPin(entered) },
            onSuccess = {
                pendingPinProtectedAction = null
                pendingAction()
            },
            onDismiss = { pendingPinProtectedAction = null }
        )
    }
}

@Composable
private fun SettingsRowCard(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 3. Connected Platforms Manager & Custom Platform Addition (`ConnectedPlatformsScreen.kt`).
 * Displays a 2-column grid of built-in & custom platforms with `+ Add Platform` and `Add Shortcut` actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedPlatformsScreen(
    state: AmnGazeState,
    viewModel: AmnGazeViewModel,
    onSaveAndReturn: () -> Unit
) {
    val context = LocalContext.current
    var showAddPlatformDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Connected Platforms", fontWeight = FontWeight.Bold)
                        Text(
                            "Enable platforms, pin launcher shortcuts, or add custom sites",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onSaveAndReturn) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { showAddPlatformDialog = true },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("add_platform_top_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Platform", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Platform")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onSaveAndReturn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp)
                        .testTag("connected_platforms_save_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp)
                .testTag("connected_platforms_grid"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            items(state.connectedPlatforms, key = { it.id }) { platform ->
                ConnectedPlatformGridCard(
                    platform = platform,
                    onToggle = { viewModel.toggleConnectedPlatformEnabled(platform.id) },
                    onAddShortcut = {
                        val pinned = ShortcutHelper.requestPinPlatformShortcut(context, platform)
                        val msg = if (pinned) {
                            "Shortcut request sent for ${platform.name}"
                        } else {
                            "Launcher shortcut created for ${platform.name}"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    if (showAddPlatformDialog) {
        AddCustomPlatformDialog(
            existingIds = state.connectedPlatforms.map { it.id }.toSet(),
            onAddPlatform = { name, url, id ->
                viewModel.addOrUpdateCustomPlatform(name, url, id)
                showAddPlatformDialog = false
            },
            onDismiss = { showAddPlatformDialog = false }
        )
    }
}

@Composable
private fun ConnectedPlatformGridCard(
    platform: ConnectedPlatform,
    onToggle: () -> Unit,
    onAddShortcut: () -> Unit
) {
    val borderColor = if (platform.enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .testTag("platform_card_${platform.id}"),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (platform.enabled) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconForPlatform(platform.id),
                        contentDescription = platform.name,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                RadioButton(
                    selected = platform.enabled,
                    onClick = onToggle,
                    modifier = Modifier.testTag("platform_toggle_${platform.id}")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = platform.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = platform.url.removePrefix("https://").removePrefix("www."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onAddShortcut,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("platform_shortcut_${platform.id}"),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AddToHomeScreen,
                    contentDescription = "Add Shortcut",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Shortcut", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AddCustomPlatformDialog(
    existingIds: Set<String>,
    onAddPlatform: (name: String, url: String, id: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    val presets = remember(existingIds) {
        ConnectedPlatform.presetCatalog().filter { it.id !in existingIds }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Platform or Social App", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Quick-Add Popular Social & Search Presets:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddPlatform(preset.name, preset.url, preset.id) }
                                .testTag("preset_platform_${preset.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddCircleOutline,
                                    contentDescription = preset.name,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, fontWeight = FontWeight.SemiBold)
                                    Text(preset.url, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text(
                    "Or Enter Custom Platform Name & URL:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("Platform Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_platform_name_input")
                )
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("Website URL (https://...)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_platform_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (customName.isNotBlank() && customUrl.isNotBlank()) {
                        onAddPlatform(customName, customUrl, null)
                    }
                },
                enabled = customName.isNotBlank() && customUrl.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_custom_platform_button")
            ) {
                Text("Add Custom")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * 4. In-App Protected WebView & Floating `Platforms` Switcher (`InAppBrowserScreen.kt`).
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppBrowserScreen(
    state: AmnGazeState,
    viewModel: AmnGazeViewModel,
    initialUrlOverride: String? = null,
    onOpenSettings: () -> Unit,
    onOpenManagePlatforms: () -> Unit,
    onReturnToSystemDashboard: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val moderationEngine = remember { ContentModerationEngine(context.applicationContext) }
    val urlScanner = remember { FastPathUrlAndKeywordScanner() }
    val strikeLockoutManager = remember { StrikeLockoutManager() }

    val enabledPlatforms = remember(state.connectedPlatforms) {
        state.connectedPlatforms.filter { it.enabled }.ifEmpty { ConnectedPlatform.defaultPlatforms() }
    }
    val activePlatform = remember(enabledPlatforms, state.activePlatformId) {
        enabledPlatforms.find { it.id == state.activePlatformId } ?: enabledPlatforms.first()
    }

    var currentUrl by remember(activePlatform.id, initialUrlOverride) {
        mutableStateOf(initialUrlOverride ?: activePlatform.url)
    }
    var blockedNavigationMatch by remember { mutableStateOf<ViolationMatch?>(null) }
    var blockedTargetUri by remember { mutableStateOf<String?>(null) }
    var showPlatformsSheet by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val moderationBridge = remember {
        WebViewModerationBridge(
            context = context,
            scope = coroutineScope,
            moderationEngine = moderationEngine,
            stateProvider = { viewModel.state.value },
            onDetectionRecorded = { viewModel.refreshProtectionMetrics() }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            moderationBridge.detachWebView()
            moderationEngine.close()
        }
    }

    // Track active browsing time every 5 seconds for Usage & Time Limits
    val isDailyLimitReached = PlatformUsageTracker.isDailyLimitReached(
        platformId = activePlatform.id,
        dailyLimitMinutes = activePlatform.dailyLimitMinutes,
        usageSecondsMap = state.platformDailyUsageSeconds
    )

    LaunchedEffect(activePlatform.id, isDailyLimitReached) {
        while (!isDailyLimitReached) {
            delay(5_000L)
            viewModel.recordPlatformUsageSeconds(activePlatform.id, 5)
        }
    }

    BackHandler {
        val wv = webViewRef
        if (blockedNavigationMatch != null) {
            blockedNavigationMatch = null
            blockedTargetUri = null
        } else if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (onReturnToSystemDashboard != null) {
            onReturnToSystemDashboard()
        } else {
            showPlatformsSheet = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .testTag("in_app_browser_screen")
    ) {
        val activeViolation = blockedNavigationMatch
        if (isDailyLimitReached) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.TimerOff,
                    contentDescription = "Daily Limit Reached",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Daily Time Limit Reached for ${activePlatform.name}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "You have reached your ${activePlatform.dailyLimitMinutes}-minute daily browsing limit for ${activePlatform.name}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = { showPlatformsSheet = true }) {
                    Text("Switch Platform or Open Settings")
                }
            }
        } else if (activeViolation != null) {
            val canSnoozeKeyword = !state.parentalPinEnabled &&
                StrikeLockoutManager.canSnoozeViolationFor30s(activeViolation.triggerSource)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp)
                    .testTag("in_app_blocked_url_shield"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Blocked Adult URL",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (activeViolation.triggerSource == ViolationTriggerSource.ADULT_DOMAIN_URL) {
                        "Explicit Domain Blocked by AmnGaze"
                    } else {
                        "Explicit Keyword Blocked by AmnGaze"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = activeViolation.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        blockedNavigationMatch = null
                        blockedTargetUri = null
                        webViewRef?.loadUrl(activePlatform.url)
                    }
                ) {
                    Text("Return to ${activePlatform.name}")
                }
                if (canSnoozeKeyword) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            strikeLockoutManager.snoozeKeywordTextOnly(activePlatform.id, now)
                            val resumeUri = blockedTargetUri
                            blockedNavigationMatch = null
                            blockedTargetUri = null
                            if (!resumeUri.isNullOrBlank()) {
                                currentUrl = resumeUri
                                webViewRef?.loadUrl(resumeUri)
                            }
                        },
                        modifier = Modifier.testTag("in_app_skip_keyword_30s_button")
                    ) {
                        Text("Skip Keyword Alert for 30s")
                    }
                }
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("protected_webview_container"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        if (!ContentModerationEngine.hasRenderNodeSupport()) {
                            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadsImagesAutomatically = true
                        addJavascriptInterface(moderationBridge, "AmnGazeBridge")

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val targetUri = request?.url?.toString() ?: return false
                                if (WhatToProtectItem.EXPLICIT_TEXT_URLS in viewModel.state.value.whatToProtect) {
                                    val now = System.currentTimeMillis()
                                    val isKeywordSnoozed = strikeLockoutManager.isKeywordTextSnoozed(
                                        activePlatform.id,
                                        now
                                    )
                                    val violationMatch = urlScanner.findViolationMatch(
                                        rawInput = targetUri,
                                        isUrlBarNode = true,
                                        isKeywordSnoozed = isKeywordSnoozed
                                    )
                                    if (violationMatch != null &&
                                        strikeLockoutManager.shouldBlockViolation(
                                            activePlatform.id,
                                            violationMatch.triggerSource,
                                            now
                                        )
                                    ) {
                                        blockedTargetUri = targetUri
                                        blockedNavigationMatch = violationMatch
                                        return true
                                    }
                                }
                                currentUrl = targetUri
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (url != null) currentUrl = url
                                injectDomProtectionScript(view, viewModel.state.value)
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                super.onPageCommitVisible(view, url)
                                injectDomProtectionScript(view, viewModel.state.value)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                injectDomProtectionScript(view, viewModel.state.value)
                            }
                        }

                        moderationBridge.attachWebView(this)
                        webViewRef = this
                        loadUrl(currentUrl)
                    }
                },
                update = { wv ->
                    moderationBridge.attachWebView(wv)
                    injectDomProtectionScript(wv, state)
                }
            )
        }

        // Bottom-Right Floating Platform Badge Button with Gear Overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            FloatingActionButton(
                onClick = { showPlatformsSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .size(60.dp)
                    .testTag("floating_platforms_fab")
            ) {
                Icon(
                    imageVector = iconForPlatform(activePlatform.id),
                    contentDescription = "Open Platforms Switcher",
                    modifier = Modifier.size(28.dp)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    if (showPlatformsSheet) {
        PlatformsBottomSheet(
            enabledPlatforms = enabledPlatforms,
            activePlatformId = activePlatform.id,
            currentUrl = currentUrl,
            onSelectPlatform = { selected ->
                viewModel.setActivePlatform(selected.id)
                blockedNavigationMatch = null
                blockedTargetUri = null
                currentUrl = selected.url
                webViewRef?.loadUrl(selected.url)
                showPlatformsSheet = false
            },
            onOpenSettings = {
                showPlatformsSheet = false
                onOpenSettings()
            },
            onOpenManage = {
                showPlatformsSheet = false
                onOpenManagePlatforms()
            },
            onDismiss = { showPlatformsSheet = false }
        )
    }
}

private fun injectDomProtectionScript(webView: WebView?, state: AmnGazeState) {
    val wv = webView ?: return
    val script = WebViewModerationBridge.generateDomObserverJavascript(
        whatToProtect = state.whatToProtect,
        blurStyle = state.haramBlurOverlayStyle,
        strengthPx = state.inAppBlurStrengthPx,
        feedControlConfig = state.feedControlConfig,
        preBlurNewMediaUntilScanned = state.preBlurNewMediaUntilScanned
    )
    wv.evaluateJavascript(script, null)
}

/**
 * `Platforms` Modal Bottom Sheet (`PlatformsBottomSheet.kt`).
 * Features `[Gear] Settings`, `[+] Manage`, `[Copy] Copy Current Page URL`, and 3-column platform grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformsBottomSheet(
    enabledPlatforms: List<ConnectedPlatform>,
    activePlatformId: String,
    currentUrl: String,
    onSelectPlatform: (ConnectedPlatform) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenManage: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("platforms_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Platforms",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Switch platforms or jump into settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Top Action Row: [Gear] Settings & [+] Manage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sheet_settings_button"),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Settings")
                }

                FilledTonalButton(
                    onClick = onOpenManage,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sheet_manage_button"),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Manage", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Manage")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Middle Full-Width Button: Copy Current Page URL
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AmnGaze URL", currentUrl))
                    Toast.makeText(context, "Copied URL to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sheet_copy_url_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Current Page URL")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom 3-Column Platform Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(enabledPlatforms, key = { it.id }) { platform ->
                    val isSelected = platform.id == activePlatformId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPlatform(platform) }
                            .testTag("sheet_platform_${platform.id}"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = iconForPlatform(platform.id),
                                contentDescription = platform.name,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = platform.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// --- Shared Personalization & Parental Control Dialogs ---

@Composable
fun WhoToBlurSelectorDialog(
    currentGender: HaramBlurTargetGender,
    onSelectGender: (HaramBlurTargetGender) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who to Blur", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose which faces/figures you want to blur for your comfort.",
                    style = MaterialTheme.typography.bodySmall
                )
                HaramBlurTargetGender.entries.forEach { option ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectGender(option) }
                            .testTag("dialog_who_to_blur_${option.name.lowercase()}"),
                        border = BorderStroke(
                            if (option == currentGender) 2.dp else 1.dp,
                            if (option == currentGender) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option == currentGender, onClick = { onSelectGender(option) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(option.displayName, fontWeight = FontWeight.Bold)
                                Text(option.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun DetectionSensitivitySelectorDialog(
    currentSensitivity: HaramBlurSensitivity,
    onSelectSensitivity: (HaramBlurSensitivity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detection Sensitivity", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HaramBlurSensitivity.entries.forEach { item ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSensitivity(item) }
                            .testTag("dialog_sensitivity_${item.name.lowercase()}"),
                        border = BorderStroke(
                            if (item == currentSensitivity) 2.dp else 1.dp,
                            if (item == currentSensitivity) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = item == currentSensitivity, onClick = { onSelectSensitivity(item) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(item.displayName, fontWeight = FontWeight.Bold)
                                Text(item.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun BlurStyleSheetDialog(
    currentStyle: HaramBlurOverlayStyle,
    currentStrengthPx: Int,
    preBlurNewMedia: Boolean = false,
    onUpdateStyle: (HaramBlurOverlayStyle) -> Unit,
    onUpdateStrengthPx: (Int) -> Unit,
    onTogglePreBlurNewMedia: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val strength10 = ((currentStrengthPx + 1) / 2).coerceIn(1, 10)
    val solidnessPct = HaramBlurFilterLogic.computeOverlaySolidnessPercent(strength10)
    val solidnessDesc = if (solidnessPct >= 100) "100% Solid (No See-Through)" else "${solidnessPct}% Solidness"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Blur Style & Effect Strength", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Overlay Style:", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HaramBlurOverlayStyle.entries.forEach { style ->
                        val selected = style == currentStyle
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateStyle(style) },
                            label = { Text(style.displayName, fontSize = 11.sp) }
                        )
                    }
                }
                Text(
                    "Effect Strength: ${strength10}/10 (${currentStrengthPx}px | $solidnessDesc)",
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = currentStrengthPx.toFloat(),
                    onValueChange = { onUpdateStrengthPx(it.toInt().coerceIn(1, 20)) },
                    valueRange = 1f..20f,
                    steps = 18
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTogglePreBlurNewMedia(!preBlurNewMedia) }
                        .testTag("dialog_preblur_new_media_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pre-Blur New Media Until Scanned", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Blur newly appearing images and videos until AI verification finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = preBlurNewMedia,
                        onCheckedChange = { onTogglePreBlurNewMedia(it) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun WhatToProtectSelectorDialog(
    selectedItems: Set<WhatToProtectItem>,
    onToggleItem: (WhatToProtectItem) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What to Protect", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WhatToProtectItem.entries.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleItem(item) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item in selectedItems,
                            onCheckedChange = { onToggleItem(item) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(item.displayName, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun FeedControlDialog(
    config: FeedControlConfig,
    onUpdateConfig: (FeedControlConfig) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Feed Control", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FeedToggleRow(
                    title = "Hide YouTube Shorts",
                    checked = config.hideYoutubeShorts,
                    onCheckedChange = { onUpdateConfig(config.copy(hideYoutubeShorts = it)) }
                )
                FeedToggleRow(
                    title = "Hide Instagram Reels & Explore",
                    checked = config.hideInstagramReels,
                    onCheckedChange = { onUpdateConfig(config.copy(hideInstagramReels = it)) }
                )
                FeedToggleRow(
                    title = "Hide For You / Algorithmic Trends",
                    checked = config.hideAlgorithmicFeeds,
                    onCheckedChange = { onUpdateConfig(config.copy(hideAlgorithmicFeeds = it)) }
                )
                FeedToggleRow(
                    title = "Hide Comments Sections",
                    checked = config.hideComments,
                    onCheckedChange = { onUpdateConfig(config.copy(hideComments = it)) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun FeedToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun UsageTimeLimitsDialog(
    platforms: List<ConnectedPlatform>,
    usageSecondsMap: Map<String, Int>,
    onUpdateLimit: (String, Int) -> Unit,
    onResetUsage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val limitOptions = listOf(0, 15, 30, 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usage & Daily Time Limits", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(platforms, key = { it.id }) { platform ->
                    val usedMins = ((usageSecondsMap[platform.id] ?: 0) / 60)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(platform.name, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Used today: ${usedMins}m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            limitOptions.forEach { mins ->
                                val label = if (mins == 0) "Unlimited" else "${mins}m"
                                FilterChip(
                                    selected = platform.dailyLimitMinutes == mins,
                                    onClick = { onUpdateLimit(platform.id, mins) },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }
                        if (usedMins > 0) {
                            TextButton(onClick = { onResetUsage(platform.id) }) {
                                Text("Reset Today's Counter", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun ParentalControlsDialog(
    isPinEnabled: Boolean,
    onEnablePin: (String) -> Boolean,
    onDisablePin: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isPinEnabled) "Disable Parental Control PIN" else "Set 4-Digit Parental PIN",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isPinEnabled) {
                        "Enter your current 4-digit PIN to unlock and disable Parental Controls."
                    } else {
                        "Protect Master Toggles, protection mode switching, settings edits, and unblur actions with a 4-digit PIN."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = {
                        if (it.length <= 4 && it.all { ch -> ch.isDigit() }) {
                            pinInput = it
                            errorText = null
                        }
                    },
                    label = { Text("4-Digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("parental_pin_input")
                )
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val ok = if (isPinEnabled) onDisablePin(pinInput) else onEnablePin(pinInput)
                    if (ok) {
                        onDismiss()
                    } else {
                        errorText = if (isPinEnabled) "Incorrect PIN code." else "Please enter exactly 4 digits."
                    }
                },
                modifier = Modifier.testTag("parental_pin_confirm_button")
            ) {
                Text(if (isPinEnabled) "Disable Lock" else "Enable Lock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ParentalPinPromptDialog(
    onVerifyPin: (String) -> Boolean,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parental Control PIN Required", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter your 4-digit Parental Control PIN to modify protection settings.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = {
                        if (it.length <= 4 && it.all { ch -> ch.isDigit() }) {
                            enteredPin = it
                            errorMsg = null
                        }
                    },
                    label = { Text("4-Digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("verify_pin_input")
                )
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (onVerifyPin(enteredPin)) {
                        onSuccess()
                    } else {
                        errorMsg = "Incorrect PIN. Please try again."
                    }
                },
                modifier = Modifier.testTag("verify_pin_submit_button")
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AutoRunAppPickerDialog(
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val candidateApps = remember {
        listOf(
            "com.instagram.android" to "Instagram",
            "com.twitter.android" to "X (Twitter)",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.reddit.frontpage" to "Reddit",
            "com.facebook.katana" to "Facebook",
            "com.google.android.youtube" to "YouTube",
            "com.android.chrome" to "Google Chrome",
            "org.mozilla.firefox" to "Firefox Browser",
            "com.brave.browser" to "Brave Browser",
            "com.sec.android.app.sbrowser" to "Samsung Internet",
            "com.snapchat.android" to "Snapchat",
            "com.pinterest" to "Pinterest",
            "org.telegram.messenger" to "Telegram",
            "com.discord" to "Discord",
            "com.google.android.apps.photos" to "Google Photos"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Auto-Run Apps", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Select apps that when opened, AmnGaze will remind you to enable HaramBlur detection or focus Selected-Apps protection:",
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    items(candidateApps, key = { it.first }) { (pkg, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTogglePackage(pkg) }
                                .padding(vertical = 6.dp)
                                .testTag("autorun_item_$pkg"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = pkg in selectedPackages,
                                onCheckedChange = { onTogglePackage(pkg) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(label, fontWeight = FontWeight.SemiBold)
                                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

private fun iconForPlatform(id: String): ImageVector {
    return when (id.lowercase()) {
        "facebook" -> Icons.Default.Groups
        "instagram" -> Icons.Default.PhotoCamera
        "youtube" -> Icons.Default.PlayCircleFilled
        "google" -> Icons.Default.Search
        "x_twitter" -> Icons.Default.Tag
        "linkedin" -> Icons.Default.Work
        "reddit" -> Icons.Default.Forum
        "tiktok" -> Icons.Default.VideoLibrary
        else -> Icons.Default.Public
    }
}

private fun openDefaultAppLinkSettings(context: Context) {
    try {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        } catch (_: Throwable) {
        }
    }
}
