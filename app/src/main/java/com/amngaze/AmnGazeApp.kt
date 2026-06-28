package com.amngaze

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amngaze.ui.theme.AmnGazeTheme
import com.amngaze.ui.theme.*
import com.amngaze.ui.components.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

enum class Screen {
    WELCOME,
    QUICK_SETUP,
    MAIN_DASHBOARD
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmnGazeApp(
    viewModel: AmnGazeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var currentScreen by remember { mutableStateOf(Screen.WELCOME) }
    var previousScreen by remember { mutableStateOf<Screen?>(null) }

    val context = LocalContext.current
    val isAmnGazeRunning by ProtectionOverlayService.isRunning.collectAsState()

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ProtectionOverlayService::class.java).apply {
                action = ProtectionOverlayService.ACTION_START
                putExtra(ProtectionOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ProtectionOverlayService.EXTRA_DATA_INTENT, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Toast.makeText(context, "AmnGaze Active Protection: ON", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "AmnGaze projection permission denied.", Toast.LENGTH_LONG).show()
        }
    }

    val toggleAmnGaze = {
        if (isAmnGazeRunning) {
            val serviceIntent = Intent(context, ProtectionOverlayService::class.java).apply {
                action = ProtectionOverlayService.ACTION_STOP
            }
            context.stopService(serviceIntent)
            Toast.makeText(context, "AmnGaze Active Protection: OFF", Toast.LENGTH_SHORT).show()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Overlay Window permission required for AmnGaze AI.", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            } else {
                val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    LaunchedEffect(state.isSetupComplete) {
        if (state.isSetupComplete && currentScreen == Screen.WELCOME) {
            currentScreen = Screen.MAIN_DASHBOARD
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
                        previousScreen = Screen.WELCOME
                        currentScreen = Screen.QUICK_SETUP
                    }
                )

                Screen.QUICK_SETUP -> QuickSetupScreen(
                    state = state,
                    onContinue = {
                        viewModel.completeSetup()
                        currentScreen = Screen.MAIN_DASHBOARD
                    },
                    onSkip = {
                        viewModel.completeSetup()
                        currentScreen = Screen.MAIN_DASHBOARD
                    }
                )

                Screen.MAIN_DASHBOARD -> MainDashboardScreen(
                    state = state,
                    viewModel = viewModel,
                    isAmnGazeRunning = isAmnGazeRunning,
                    onToggleAmnGaze = toggleAmnGaze
                )
            }
        }
    }
}

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(32.dp))

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
                    text = "Welcome to AmnGaze AI Blur",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Your intelligent companion featuring low-latency, on-device explicit image blurring and Content Protection.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
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
                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun QuickSetupScreen(
    state: AmnGazeState,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var batteryGranted by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }
    var notificationsGranted by remember { mutableStateOf(isNotificationPermissionGranted(context)) }
    var overlayGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else true
        )
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsGranted = granted }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryGranted = isBatteryOptimizationIgnored(context)
                notificationsGranted = isNotificationPermissionGranted(context)
                overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Settings.canDrawOverlays(context) else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val canProceed = overlayGranted

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
            Column {
                Text(
                    text = "Quick Setup",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "We need Draw Over Other Apps permission to place the censoring overlay over explicit content.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(28.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column {
                        // 1. Draw Over Other Apps
                        PermissionRow(
                            icon = Icons.Default.Layers,
                            title = "Draw Over Other Apps",
                            description = "Required for AI blur overlay",
                            isRequired = true,
                            isDone = overlayGranted,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                                    )
                                }
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 2. Battery Optimization
                        PermissionRow(
                            icon = Icons.Default.BatteryFull,
                            title = "Battery Optimization",
                            description = "Keeps shields active in background",
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
                                    } catch (e: Exception) {
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
                            title = "Notifications Alerts",
                            description = "Required for active protection status indicator",
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
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(visible = !overlayGranted) {
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
                                text = "Please enable \"Draw Over Other Apps\" to allow blurring content.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Button(
                    onClick = onContinue,
                    enabled = canProceed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canProceed) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = if (canProceed) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Skip for now",
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
                        isDone -> "Done"
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
    onToggleAmnGaze: () -> Unit
) {
    var showThemeMenu by remember { mutableStateOf(false) }

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
                            text = "AmnGaze AI Blur",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showThemeMenu = true }) {
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
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAmnGazeRunning) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        }
                    ),
                    border = BorderStroke(
                        1.5.dp,
                        if (isAmnGazeRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "radar")
                        val radarScale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.25f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = EaseOutSine),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "radarScale"
                        )
                        val radarAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = EaseOutSine),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "radarAlpha"
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(100.dp)
                        ) {
                            if (isAmnGazeRunning) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .scale(radarScale)
                                        .drawBehind {
                                            drawCircle(
                                                color = SunsetPrimary.copy(alpha = radarAlpha),
                                                radius = size.minDimension / 2
                                            )
                                        }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(70.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isAmnGazeRunning) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                    .clickable { onToggleAmnGaze() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isAmnGazeRunning) Icons.Default.Shield else Icons.Default.PrivacyTip,
                                    contentDescription = "Toggle",
                                    tint = if (isAmnGazeRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (isAmnGazeRunning) "AI Blur Active" else "AI Protection Paused",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAmnGazeRunning) "On-device AI content blurring is active."
                                   else "Tap the shield button to start real-time image censorship.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = onToggleAmnGaze,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAmnGazeRunning) MaterialTheme.colorScheme.error
                                                 else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = if (isAmnGazeRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isAmnGazeRunning) "STOP PROTECTION" else "START PROTECTION",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF0A0A1E)
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF3A3A5C))
                            )
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .width(50.dp)
                                    .height(60.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF2E2E4A))
                            )
                        }

                        val previewBoxes = when (state.amnGazeBlurMode) {
                            BlurTargetMode.FULL_SCREEN -> listOf(
                                (0.02f to 0.02f) to (0.98f to 0.98f)
                            )
                            BlurTargetMode.FULL_PERSON -> listOf(
                                (0.25f to 0.06f) to (0.75f to 0.96f)
                            )
                            BlurTargetMode.BODY_REGION -> listOf(
                                (0.32f to 0.14f) to (0.68f to 0.30f),
                                (0.16f to 0.12f) to (0.31f to 0.50f),
                                (0.69f to 0.12f) to (0.84f to 0.50f),
                                (0.27f to 0.30f) to (0.73f to 0.60f),
                                (0.25f to 0.60f) to (0.75f to 0.84f)
                            )
                        }

                        previewBoxes.forEach { (tl, br) ->
                            val (left, top) = tl
                            val (right, bottom) = br
                            val censorColor = when (state.amnGazeCensorStyle) {
                                BlurCensorStyle.MOSAIC -> Color(0xFF1A1A2E)
                                BlurCensorStyle.GAUSSIAN_BLUR -> Color(0xFF1A1A2E).copy(alpha = 0.6f)
                                BlurCensorStyle.BLACK_BAR -> Color.Black.copy(alpha = 0.85f)
                                BlurCensorStyle.FROSTED -> Color.White.copy(alpha = 0.8f)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = (left * 100).toInt().coerceIn(0, 98).dp,
                                        top = (top * 100).toInt().coerceIn(0, 98).dp,
                                        end = ((1f - right) * 100).toInt().coerceIn(0, 98).dp,
                                        bottom = ((1f - bottom) * 100).toInt().coerceIn(0, 98).dp
                                    )
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(censorColor)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Live Preview — ${when (state.amnGazeBlurMode) {
                                    BlurTargetMode.BODY_REGION -> "Body Region Mode"
                                    BlurTargetMode.FULL_PERSON -> "Full Person Mode"
                                    BlurTargetMode.FULL_SCREEN -> "Full Screen Mode"
                                }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "DETECTION TARGET",
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
                        BlurTargetMode.entries.forEachIndexed { index, mode ->
                            val (title, description, icon) = when (mode) {
                                BlurTargetMode.BODY_REGION -> Triple(
                                    "Body Region (Recommended)",
                                    "Blurs individual exposed skin patches",
                                    Icons.Default.CropFree
                                )
                                BlurTargetMode.FULL_PERSON -> Triple(
                                    "Full Person Silhouette",
                                    "Blurs the entire person silhouette when detected",
                                    Icons.Default.Person
                                )
                                BlurTargetMode.FULL_SCREEN -> Triple(
                                    "Entire Screen",
                                    "Pixelates the full screen when explicit content is found",
                                    Icons.Default.Fullscreen
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateBlurMode(mode) }
                                    .background(
                                        if (state.amnGazeBlurMode == mode)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.amnGazeBlurMode == mode,
                                    onClick = { viewModel.updateBlurMode(mode) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(Modifier.width(12.dp))
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (state.amnGazeBlurMode == mode)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.amnGazeBlurMode == mode)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (index < BlurTargetMode.entries.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "CENSOR STYLE",
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
                            BlurCensorStyle.MOSAIC -> "Mosaic" to Icons.Default.Grid4x4
                            BlurCensorStyle.GAUSSIAN_BLUR -> "Soft Blur" to Icons.Default.BlurOn
                            BlurCensorStyle.BLACK_BAR -> "Black Bar" to Icons.Default.HideImage
                            BlurCensorStyle.FROSTED -> "Frosted" to Icons.Default.AcUnit
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
                                .padding(vertical = 14.dp),
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
                                "Confidence Threshold",
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
                                Text(
                                    "${(state.amnGazeBlurSensitivity * 100).toInt()}%",
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
                                "Permissive (more detections)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Strict (fewer, precise)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "VISUAL OPTIONS",
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
                            icon = Icons.Default.Badge,
                            title = "Show Alert Badge",
                            checked = state.amnGazeShowBadge,
                            onCheckedChange = { viewModel.toggleBlurBadge() }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.BorderStyle,
                            title = "Animated Alert Border",
                            checked = state.amnGazeShowBorder,
                            onCheckedChange = { viewModel.toggleBlurBorder() }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.ColorLens,
                            title = "Skin-Tone Heuristic",
                            checked = state.amnGazeSkinHeuristic,
                            onCheckedChange = { viewModel.toggleSkinHeuristic() }
                        )
                    }
                }
            }

            item {
                Text(
                    "MODERATION MODEL",
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
                                    "Standard open_nsfw model (Yahoo MobileNet V2)",
                                    Icons.Default.Security
                                )
                                ModerationModelType.QUANTIZED_NSFW -> Triple(
                                    "Quantized NSFW Filter",
                                    "Fast, low-memory quantized model (MobileNet V2 1.4)",
                                    Icons.Default.Speed
                                )
                                ModerationModelType.GENDER_FILTER -> Triple(
                                    "Gender Filter",
                                    "Gender estimation model (Female content filter)",
                                    Icons.Default.Face
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateModelType(modelType) }
                                    .background(
                                        if (state.amnGazeModelType == modelType)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.amnGazeModelType == modelType,
                                    onClick = { viewModel.updateModelType(modelType) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(Modifier.width(12.dp))
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (state.amnGazeModelType == modelType)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.amnGazeModelType == modelType)
                                            MaterialTheme.colorScheme.primary
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

            item {
                Text(
                    "HARD BLOCK MODE",
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
                            icon = Icons.Default.Block,
                            title = "Redirect to Home Screen",
                            checked = state.amnGazeHardBlockEnabled,
                            onCheckedChange = { viewModel.toggleHardBlock(it) }
                        )

                        if (state.amnGazeHardBlockEnabled) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Severity Threshold",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${(state.amnGazeHardBlockThreshold * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "When the AI is this confident, the screen will instantly go black and you will be redirected to the home screen.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.material3.Slider(
                                    value = state.amnGazeHardBlockThreshold,
                                    onValueChange = { viewModel.updateHardBlockThreshold(it) },
                                    valueRange = 0.5f..0.95f,
                                    steps = 8
                                )
                            }
                        }
                    }
                }
            }

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
                            "AmnGaze AI Blur runs entirely on-device with zero cloud upload. " +
                            "Active Model: $modelLabel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
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


fun isBatteryOptimizationIgnored(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else true
}

fun isNotificationPermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else true
}


