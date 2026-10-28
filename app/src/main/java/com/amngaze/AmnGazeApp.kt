package com.amngaze

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amngaze.ui.theme.AmnGazeTheme
import com.amngaze.ui.theme.SunsetPrimary
import androidx.compose.ui.window.DialogProperties

enum class Screen {
    WELCOME,
    PROTECTION_MODE_SELECT,
    QUICK_SETUP,
    MAIN_DASHBOARD,
    IN_APP_BROWSER,
    SETTINGS,
    CONNECTED_PLATFORMS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmnGazeApp(
    viewModel: AmnGazeViewModel = viewModel(),
    initialDeepLinkUrl: String? = null,
    initialDeepLinkPlatformId: String? = null
) {
    val state by viewModel.state.collectAsState()
    var currentScreen by remember { mutableStateOf(Screen.WELCOME) }
    var previousScreenBeforeSubRoute by remember { mutableStateOf(Screen.MAIN_DASHBOARD) }
    val activeDeepLinkUrl by remember(initialDeepLinkUrl) { mutableStateOf(initialDeepLinkUrl) }
    var isModeSelectFromSettings by remember { mutableStateOf(false) }
    var showDisclosureDialog by remember { mutableStateOf(false) }
    var showRestrictedSettingsDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isServiceConnected by AmnGazeAccessibilityService.isRunning.collectAsState()
    var isAccessibilityEnabled by remember {
        mutableStateOf(AmnGazeAccessibilityService.isServiceEnabled(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = AmnGazeAccessibilityService.isServiceEnabled(context)
                viewModel.refreshProtectionMetrics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isProtectionActive = isServiceConnected || isAccessibilityEnabled

    val requestAccessibilitySetup = {
        if (state.hasAcceptedAccessibilityDisclosure) {
            openAccessibilitySettings(context)
        } else {
            showDisclosureDialog = true
        }
    }

    LaunchedEffect(state.isSetupComplete, initialDeepLinkUrl, initialDeepLinkPlatformId) {
        if (initialDeepLinkPlatformId != null) {
            viewModel.setActivePlatform(initialDeepLinkPlatformId)
        }
        if (!initialDeepLinkUrl.isNullOrBlank()) {
            viewModel.completeSetup()
            currentScreen = Screen.IN_APP_BROWSER
        } else if (state.isSetupComplete && currentScreen == Screen.WELCOME) {
            currentScreen = if (state.appProtectionMode == AppProtectionMode.IN_APP_PROTECTION) {
                Screen.IN_APP_BROWSER
            } else {
                Screen.MAIN_DASHBOARD
            }
        }
    }

    AmnGazeTheme(appTheme = state.currentTheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (currentScreen) {
                Screen.WELCOME -> WelcomeScreen(
                    onGetStarted = {
                        isModeSelectFromSettings = false
                        currentScreen = Screen.PROTECTION_MODE_SELECT
                    }
                )

                Screen.PROTECTION_MODE_SELECT -> {
                    BackHandler {
                        currentScreen = if (isModeSelectFromSettings) {
                            Screen.SETTINGS
                        } else {
                            Screen.WELCOME
                        }
                    }
                    ProtectionModeScreen(
                        initialMode = state.appProtectionMode,
                        onSaveMode = { chosenMode ->
                            viewModel.updateAppProtectionMode(chosenMode)
                            if (isModeSelectFromSettings) {
                                isModeSelectFromSettings = false
                                currentScreen = if (chosenMode == AppProtectionMode.IN_APP_PROTECTION) {
                                    Screen.IN_APP_BROWSER
                                } else {
                                    Screen.MAIN_DASHBOARD
                                }
                            } else if (chosenMode == AppProtectionMode.IN_APP_PROTECTION) {
                                // Skip mandatory system permission gates in In-App Protection mode
                                viewModel.completeSetup()
                                currentScreen = Screen.IN_APP_BROWSER
                            } else {
                                currentScreen = Screen.QUICK_SETUP
                            }
                        },
                        onBack = if (isModeSelectFromSettings) {
                            { currentScreen = Screen.SETTINGS }
                        } else {
                            { currentScreen = Screen.WELCOME }
                        }
                    )
                }

                Screen.QUICK_SETUP -> {
                    BackHandler {
                        currentScreen = Screen.PROTECTION_MODE_SELECT
                    }
                    QuickSetupScreen(
                        isAccessibilityEnabled = isProtectionActive,
                        onOpenAccessibility = requestAccessibilitySetup,
                        onOpenRestrictedSettingsHelp = { showRestrictedSettingsDialog = true },
                        onContinue = {
                            viewModel.completeSetup()
                            currentScreen = Screen.MAIN_DASHBOARD
                        },
                        onSkip = {
                            viewModel.completeSetup()
                            currentScreen = Screen.MAIN_DASHBOARD
                        }
                    )
                }

                Screen.MAIN_DASHBOARD -> MainDashboardScreen(
                    state = state,
                    viewModel = viewModel,
                    isAmnGazeRunning = isProtectionActive,
                    onToggleAmnGaze = requestAccessibilitySetup,
                    onOpenRestrictedSettingsHelp = { showRestrictedSettingsDialog = true },
                    onOpenSettings = {
                        previousScreenBeforeSubRoute = Screen.MAIN_DASHBOARD
                        currentScreen = Screen.SETTINGS
                    },
                    onOpenInAppBrowser = {
                        previousScreenBeforeSubRoute = Screen.MAIN_DASHBOARD
                        currentScreen = Screen.IN_APP_BROWSER
                    }
                )

                Screen.IN_APP_BROWSER -> InAppBrowserScreen(
                    state = state,
                    viewModel = viewModel,
                    initialUrlOverride = activeDeepLinkUrl,
                    onOpenSettings = {
                        previousScreenBeforeSubRoute = Screen.IN_APP_BROWSER
                        currentScreen = Screen.SETTINGS
                    },
                    onOpenManagePlatforms = {
                        previousScreenBeforeSubRoute = Screen.IN_APP_BROWSER
                        currentScreen = Screen.CONNECTED_PLATFORMS
                    },
                    onReturnToSystemDashboard = if (state.appProtectionMode == AppProtectionMode.SYSTEM_WIDE_PROTECTION) {
                        { currentScreen = Screen.MAIN_DASHBOARD }
                    } else {
                        null
                    }
                )

                Screen.SETTINGS -> {
                    BackHandler {
                        currentScreen = if (state.appProtectionMode == AppProtectionMode.IN_APP_PROTECTION) {
                            Screen.IN_APP_BROWSER
                        } else {
                            Screen.MAIN_DASHBOARD
                        }
                    }
                    SettingsScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenProtectionModeChooser = {
                            isModeSelectFromSettings = true
                            currentScreen = Screen.PROTECTION_MODE_SELECT
                        },
                        onOpenConnectedPlatforms = {
                            previousScreenBeforeSubRoute = Screen.SETTINGS
                            currentScreen = Screen.CONNECTED_PLATFORMS
                        },
                        onOpenSystemWideDashboard = {
                            currentScreen = Screen.MAIN_DASHBOARD
                        },
                        onBack = {
                            currentScreen = if (state.appProtectionMode == AppProtectionMode.IN_APP_PROTECTION) {
                                Screen.IN_APP_BROWSER
                            } else {
                                Screen.MAIN_DASHBOARD
                            }
                        }
                    )
                }

                Screen.CONNECTED_PLATFORMS -> {
                    BackHandler {
                        currentScreen = previousScreenBeforeSubRoute
                    }
                    ConnectedPlatformsScreen(
                        state = state,
                        viewModel = viewModel,
                        onSaveAndReturn = {
                            currentScreen = if (state.appProtectionMode == AppProtectionMode.IN_APP_PROTECTION) {
                                Screen.IN_APP_BROWSER
                            } else {
                                previousScreenBeforeSubRoute
                            }
                        }
                    )
                }
            }

            if (showDisclosureDialog) {
                ProminentDisclosureDialog(
                    onAccept = {
                        viewModel.acceptAccessibilityDisclosure()
                        showDisclosureDialog = false
                        openAccessibilitySettings(context)
                    },
                    onDecline = {
                        showDisclosureDialog = false
                    }
                )
            }

            if (showRestrictedSettingsDialog) {
                RestrictedSettingsHelperDialog(
                    onOpenAppInfo = {
                        showRestrictedSettingsDialog = false
                        openAppDetailsSettings(context)
                    },
                    onDismiss = {
                        showRestrictedSettingsDialog = false
                    }
                )
            }
        }
    }
}

/**
 * Mandatory Google Play & Android Accessibility Prominent Disclosure Dialog.
 * Non-cancelable outside explicit Accept/Decline actions.
 */
@Composable
fun ProminentDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        icon = {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = "Accessibility Disclosure",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Accessibility Service Disclosure",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "AmnGaze uses the Android AccessibilityService API to provide real-time, on-device protection against explicit content:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "1. Fast-Path URL & Text Inspection: Reads active browser URL bars and visible headers to intercept adult domains in under 10ms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "2. On-Device LiteRT Vision Scanning: Captures periodic screen snapshots when scrolling settles in media apps to evaluate frames locally in RAM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "3. Whole-Screen Curtain Shield: Displays an instant full-screen overlay or navigates back when explicit material is detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = "100% On-Device Privacy: All URL checks and AI vision inference run locally on your device. No screenshots, URLs, or personal data ever leave your device.",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.testTag("disclosure_accept_button")
            ) {
                Text("I Agree & Open Settings")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDecline,
                modifier = Modifier.testTag("disclosure_decline_button")
            ) {
                Text("Not Now")
            }
        }
    )
}

/**
 * Helper dialog guiding users on Android 13+ (API 33+) how to unlock "Allow restricted settings"
 * when the APK is installed directly outside Google Play.
 */
@Composable
fun RestrictedSettingsHelperDialog(
    onOpenAppInfo: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = "Restricted Settings Guide",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Android 13+ Restricted Settings Guide",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "If Android greys out the AmnGaze Accessibility toggle with a \"Restricted setting\" notice, follow these 3 steps:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Step 1: Tap \"Open App Info\" below to open AmnGaze system details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Step 2: Tap the 3-dot menu icon in the top-right corner of the App Info screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Step 3: Select \"Allow restricted settings\" and authenticate with your PIN or biometric, then enable AmnGaze in Accessibility Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenAppInfo) {
                Text("Open App Info")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .drawBehind {
                            drawCircle(
                                color = SunsetPrimary.copy(alpha = 0.08f),
                                radius = size.minDimension / 1.5f
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Shield logo",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(96.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Welcome to AmnGaze AI Shield",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "3-Layer Accessibility Content Blocker & Full-Screen Curtain Shield powered by 100% on-device LiteRT AI.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("get_started_button"),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("Get Started", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun QuickSetupScreen(
    isAccessibilityEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenRestrictedSettingsHelp: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var batteryGranted by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }
    var notificationsGranted by remember { mutableStateOf(isNotificationPermissionGranted(context)) }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsGranted = granted }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryGranted = isBatteryOptimizationIgnored(context)
                notificationsGranted = isNotificationPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AmnGaze",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Quick Setup",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = "Enable the AmnGaze Accessibility Shield for zero-flicker whole-screen curtain protection and instant URL interception.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column {
                        // 1. Accessibility Shield Service
                        PermissionRow(
                            icon = Icons.Default.AccessibilityNew,
                            title = "Accessibility Shield Service",
                            description = "Required for <2ms curtain shield & local AI scan",
                            isRequired = true,
                            isDone = isAccessibilityEnabled,
                            onClick = onOpenAccessibility
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 2. Battery Optimization
                        PermissionRow(
                            icon = Icons.Default.BatteryFull,
                            title = "Battery Optimization Exemption",
                            description = "Keeps Accessibility protection responsive in background",
                            isRequired = false,
                            isDone = batteryGranted,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    try {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                                        )
                                    } catch (_: Exception) {
                                        context.startActivity(
                                            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                        )
                                    }
                                }
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 3. Notifications
                        PermissionRow(
                            icon = Icons.Default.Notifications,
                            title = "Protection Status Alerts",
                            description = "Optional alerts for cooldown lockouts",
                            isRequired = false,
                            isDone = notificationsGranted,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    )
                                }
                            }
                        )
                    }
                }

                // Android 13+ Restricted Settings Helper Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRestrictedSettingsHelp() }
                        .testTag("restricted_settings_help_card"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Restricted Settings Help",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Android 13+ \"Restricted Setting\" Greyed Out?",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tap here for the 3-step guide to enable Allow Restricted Settings in App Info.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(visible = !isAccessibilityEnabled) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Tap \"Accessibility Shield Service\" above to review the disclosure and activate protection.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Button(
                    onClick = onContinue,
                    enabled = isAccessibilityEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("setup_continue_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = if (isAccessibilityEnabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text("Continue to Dashboard", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                TextButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setup_skip_button")
                ) {
                    Text(
                        "Configure Dashboard First",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String = "",
    isRequired: Boolean,
    isDone: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isDone) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isDone) Icons.Default.Check else icon,
                    contentDescription = null,
                    tint = if (isDone) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(99.dp),
            color = when {
                isDone -> MaterialTheme.colorScheme.primary
                isRequired -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            },
            border = if (isDone || isRequired) null
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDone) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = when {
                        isDone -> "Active"
                        isRequired -> "Required"
                        else -> "Optional"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isDone -> MaterialTheme.colorScheme.onPrimary
                        isRequired -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    state: AmnGazeState,
    viewModel: AmnGazeViewModel,
    isAmnGazeRunning: Boolean,
    onToggleAmnGaze: () -> Unit,
    onOpenRestrictedSettingsHelp: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenInAppBrowser: () -> Unit = {}
) {
    var showThemeMenu by remember { mutableStateOf(false) }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "AmnGaze AI Shield",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("dashboard_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenRestrictedSettingsHelp) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Restricted Settings Guide",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showThemeMenu = true },
                            modifier = Modifier.testTag("theme_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Switch Theme",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false }
                        ) {
                            AppTheme.entries.forEach { theme ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = when (theme) {
                                                AppTheme.SUNSET_GLOW -> "Sunset Glow"
                                                AppTheme.EMERALD_CALM -> "Emerald Calm"
                                                AppTheme.COSMIC_NIGHT -> "Cosmic Night"
                                            }
                                        )
                                    },
                                    onClick = {
                                        viewModel.updateTheme(theme)
                                        showThemeMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .testTag("dashboard_lazy_column"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)
        ) {
            // Quick-Launch Card for Protected In-App Browser Hub
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenInAppBrowser() }
                        .testTag("open_in_app_browser_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = "In-App Protected Browser Hub",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Protected In-App Social & Browser Hub",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Browse ${state.enabledPlatformsSummary} with 120Hz DOM-locked blur & Feed Control",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Browser Hub",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // =========================================================================
            // MODULE 1: EXPLICIT & ADULT CONTENT BLOCKER (3-LAYER ACCESSIBILITY SHIELD)
            // =========================================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("master_card_explicit_blocker"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.isExplicitBlockerEnabled && isAmnGazeRunning) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        }
                    ),
                    border = BorderStroke(
                        1.5.dp,
                        if (state.isExplicitBlockerEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (state.isExplicitBlockerEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                    .clickable { onToggleAmnGaze() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.isExplicitBlockerEnabled) Icons.Default.Shield else Icons.Default.PrivacyTip,
                                    contentDescription = "Toggle Accessibility Protection",
                                    tint = if (state.isExplicitBlockerEnabled) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.toggleExplicitSettingsDropdown() }
                            ) {
                                Text(
                                    text = "MASTER MODULE 1: 3-LAYER ACCESSIBILITY SHIELD",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Explicit & Adult Content Blocker",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = when {
                                        !state.isExplicitBlockerEnabled -> "Explicit Blocker paused by Master Toggle"
                                        isAmnGazeRunning -> "Fast-path URL filtering, multi-crop LiteRT vision & Curtain Shield active"
                                        else -> "Enable AmnGaze in Android Accessibility Settings to activate protection"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Switch(
                                checked = state.isExplicitBlockerEnabled,
                                onCheckedChange = { shouldEnable ->
                                    if (shouldEnable) {
                                        viewModel.setExplicitBlockerEnabled(true)
                                    } else {
                                        runWithPinGate {
                                            viewModel.setExplicitBlockerEnabled(false)
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("master_toggle_explicit")
                            )

                            IconButton(
                                onClick = { viewModel.toggleExplicitSettingsDropdown() },
                                modifier = Modifier.testTag("dropdown_toggle_explicit")
                            ) {
                                Icon(
                                    imageVector = if (state.isExplicitSettingsExpanded) {
                                        Icons.Default.ExpandLess
                                    } else {
                                        Icons.Default.ExpandMore
                                    },
                                    contentDescription = "Expand or Collapse Explicit Blocker Settings"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = onToggleAmnGaze,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("toggle_protection_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = if (isAmnGazeRunning) Icons.Default.SettingsAccessibility else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isAmnGazeRunning) "MANAGE ACCESSIBILITY SHIELD" else "ENABLE ACCESSIBILITY SHIELD",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (state.isExplicitSettingsExpanded) {
            // 2. Whole-Screen Curtain & Enforcement Live Preview Card
            item {
                val backdropColor = when (state.amnGazeCensorStyle) {
                    BlurCensorStyle.FROSTED -> Color(0xFF1E293B)
                    BlurCensorStyle.MOSAIC -> Color(0xFF172554)
                    BlurCensorStyle.BLACK_BAR -> Color(0xFF070B14)
                    BlurCensorStyle.GAUSSIAN_BLUR -> Color(0xFF0F172A)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = backdropColor),
                    border = BorderStroke(
                        width = if (state.amnGazeShowBorder) 2.dp else 1.dp,
                        color = if (state.amnGazeShowBorder) Color(0xFFEF4444) else Color(0xFF334155)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (state.amnGazeShowBadge) {
                                Surface(
                                    shape = RoundedCornerShape(99.dp),
                                    color = Color(0x33EF4444),
                                    border = BorderStroke(1.dp, Color(0x88EF4444))
                                ) {
                                    Text(
                                        text = "AMNGAZE AI SHIELD ACTIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFF87171),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Text(
                                text = when (state.enforcementMode) {
                                    EnforcementMode.CURTAIN_SHIELD -> "Full-Screen Curtain Shield (<2ms)"
                                    EnforcementMode.HARD_EJECT -> "Bulldog Hard Eject + 3-Strike Lockout"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Text(
                                text = when (state.enforcementMode) {
                                    EnforcementMode.CURTAIN_SHIELD ->
                                        "Covers entire screen (#F20F172A + Blur) with Leave Safely & 30s Unblur actions"
                                    EnforcementMode.HARD_EJECT ->
                                        "Strikes 1-2: Auto-Back | Strike 3 (within 5m): 15-Minute App Cooldown Lockout"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1),
                                textAlign = TextAlign.Center
                            )
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            val styleName = when (state.amnGazeCensorStyle) {
                                BlurCensorStyle.FROSTED -> "Frosted Glass"
                                BlurCensorStyle.MOSAIC -> "Mosaic Curtain"
                                BlurCensorStyle.BLACK_BAR -> "Blackout Shield"
                                BlurCensorStyle.GAUSSIAN_BLUR -> "Alert Pulse"
                            }
                            Text(
                                text = "Whole-Screen Preview - $styleName",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // 3. Enforcement Mode Selector (Curtain Shield vs. Bulldog Hard Eject)
            item {
                Text(
                    "ENFORCEMENT MODE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.08.sp
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column {
                        EnforcementMode.entries.forEachIndexed { index, mode ->
                            val (title, description, icon) = when (mode) {
                                EnforcementMode.CURTAIN_SHIELD -> Triple(
                                    "Curtain Shield Mode (Recommended)",
                                    "Instant <2ms full-screen shield with 'Leave Safely' and 'False Positive - Unblur for 30s'. Eliminates self-occlusion flicker.",
                                    Icons.Default.VisibilityOff
                                )
                                EnforcementMode.HARD_EJECT -> Triple(
                                    "Bulldog Hard Eject + 3-Strike Lockout",
                                    "Immediately navigates back on Strikes 1-2, and locks the offending app for 15 minutes on Strike 3 within 5 minutes.",
                                    Icons.Default.GppBad
                                )
                            }

                            val isSelected = state.enforcementMode == mode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateEnforcementMode(mode) }
                                    .background(
                                        if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                                    .testTag("enforcement_mode_${mode.name}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updateEnforcementMode(mode) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(Modifier.width(12.dp))
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (index < EnforcementMode.entries.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            // 4. Curtain Censor Style Selector
            item {
                Text(
                    "CURTAIN CENSOR STYLE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.08.sp
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BlurCensorStyle.entries.forEach { style ->
                        val (label, icon) = when (style) {
                            BlurCensorStyle.FROSTED -> "Frosted" to Icons.Default.AcUnit
                            BlurCensorStyle.MOSAIC -> "Mosaic" to Icons.Default.Grid4x4
                            BlurCensorStyle.BLACK_BAR -> "Blackout" to Icons.Default.HideImage
                            BlurCensorStyle.GAUSSIAN_BLUR -> "Alert Pulse" to Icons.Default.BlurOn
                        }
                        val isSelected = state.amnGazeCensorStyle == style
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { viewModel.updateCensorStyle(style) }
                                .padding(vertical = 14.dp)
                                .testTag("censor_style_${style.name}"),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 5. Detection Sensitivity Slider
            item {
                Text(
                    "DETECTION SENSITIVITY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.08.sp
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "AI Sensitivity",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                val activeThreshold = ContentModerationEngine.confidenceThresholdForSensitivity(
                                    state.amnGazeBlurSensitivity
                                )
                                Text(
                                    "${(state.amnGazeBlurSensitivity * 100).toInt()}% (Threshold: ${String.format("%.2f", activeThreshold)})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Slider(
                            value = state.amnGazeBlurSensitivity,
                            onValueChange = { viewModel.updateBlurSensitivity(it) },
                            valueRange = 0.2f..0.95f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Strict (fewer false positives)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Aggressive (higher sensitivity)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 6. 3-Layer Defense & Visual Options
            item {
                Text(
                    "3-LAYER DEFENSE & SHIELD OPTIONS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.08.sp
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column {
                        SettingsToggleRow(
                            icon = Icons.Default.Language,
                            title = "Layer 1: Fast-Path URL & 80k Domain Filter",
                            checked = state.urlFastPathEnabled,
                            onCheckedChange = { viewModel.toggleUrlFastPath() }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.SlowMotionVideo,
                            title = "Continuous Video & Media Heartbeat (1.5s)",
                            checked = state.videoHeartbeatEnabled,
                            onCheckedChange = { viewModel.toggleVideoHeartbeat() }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.Tune,
                            title = "Include Revealing / Swimwear (Sexy 0.85x Weight)",
                            checked = state.includeSexyInNsfwScore,
                            onCheckedChange = { viewModel.toggleIncludeSexyScore() }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.VpnLock,
                            title = "Block Incognito / Secure Windows",
                            checked = state.blockIncognitoEnabled,
                            onCheckedChange = { viewModel.toggleBlockIncognito() }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.Badge,
                            title = "Show Curtain Shield Badge",
                            checked = state.amnGazeShowBadge,
                            onCheckedChange = { viewModel.toggleBlurBadge() }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.BorderStyle,
                            title = "Animated Alert Border & Scanline",
                            checked = state.amnGazeShowBorder,
                            onCheckedChange = { viewModel.toggleBlurBorder() }
                        )
                    }
                }
            }

            // 7. On-Device LiteRT Moderation Model Selector
            item {
                Text(
                    "ON-DEVICE LITERT MODEL",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.08.sp
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column {
                        ModerationModelType.entries.forEachIndexed { index, modelType ->
                            val (title, description, icon) = when (modelType) {
                                ModerationModelType.DEFAULT_NSFW -> Triple(
                                    "Default NSFW Filter",
                                    "Standard MobileNet V2 NSFW model (amn_gaze.tflite)",
                                    Icons.Default.Security
                                )
                                ModerationModelType.QUANTIZED_NSFW -> Triple(
                                    "Quantized NSFW Filter",
                                    "Fast INT8/UINT8 quantized model (nsfw_mobilenet_v2_140_224_quantized.tflite)",
                                    Icons.Default.Speed
                                )
                                ModerationModelType.GENDER_FILTER -> Triple(
                                    "Gender / Revealing Filter",
                                    "Compact gender classification model (model_lite_gender_q.tflite)",
                                    Icons.Default.Face
                                )
                            }

                            val isSelected = state.amnGazeModelType == modelType
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateModelType(modelType) }
                                    .background(
                                        if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                                    .testTag("model_type_${modelType.name}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updateModelType(modelType) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(Modifier.width(12.dp))
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (index < ModerationModelType.entries.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            // 8. Strike & Lockout Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Cooldown Lockouts & Detections",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Locked Apps: ${state.activeLockedPackageCount} | Total Blocks: ${state.totalDetectionsCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.activeLockedPackageCount > 0) {
                            OutlinedButton(
                                onClick = { runWithPinGate { viewModel.clearAllLockouts() } },
                                modifier = Modifier.testTag("clear_lockouts_button")
                            ) {
                                Text("Reset Lockouts")
                            }
                        }
                    }
                }
            }
            } // End of Module 1 (Explicit Content Blocker) Collapsible Settings

            // Module 2: HaramBlur Real-Time Regional Face & Figure Blur (MediaProjection + Accessibility Hybrid)
            item {
                HaramBlurMasterModuleCard(
                    state = state,
                    viewModel = viewModel,
                    runWithPinGate = runWithPinGate
                )
            }

            // 9. Privacy & Architecture Summary Footer
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        val modelLabel = when (state.amnGazeModelType) {
                            ModerationModelType.DEFAULT_NSFW -> "Default NSFW (amn_gaze.tflite)"
                            ModerationModelType.QUANTIZED_NSFW -> "Quantized NSFW (nsfw_mobilenet_v2_140_224_quantized.tflite)"
                            ModerationModelType.GENDER_FILTER -> "Gender Filter (model_lite_gender_q.tflite)"
                        }
                        Text(
                            "AmnGaze runs 100% locally in RAM with zero cloud uploads. " +
                                "Scroll-settled screenshots (180ms settle / 750ms ceiling), MediaProjection regional frames, and HardwareBuffers are recycled immediately after inference. " +
                                "Active Model: $modelLabel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    pendingPinProtectedAction?.let { action ->
        ParentalPinPromptDialog(
            onVerifyPin = { entered -> viewModel.verifyParentalPin(entered) },
            onSuccess = {
                pendingPinProtectedAction = null
                action()
            },
            onDismiss = { pendingPinProtectedAction = null }
        )
    }
}

@Composable
fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Unable to open Accessibility Settings.", Toast.LENGTH_SHORT).show()
    }
}

fun openAppDetailsSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

fun isBatteryOptimizationIgnored(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else true
}

fun isNotificationPermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else true
}
