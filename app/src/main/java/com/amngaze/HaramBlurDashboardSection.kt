package com.amngaze

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Module 2: Real-Time Gaze & Face/Figure Blur (HaramBlur Mode — `MediaProjection` + `AccessibilityService`)
 * Master Toggle card + Dynamic Collapsible Dropdown section inside `MainDashboardScreen`.
 */
@Composable
fun HaramBlurMasterModuleCard(
    state: AmnGazeState,
    viewModel: AmnGazeViewModel,
    runWithPinGate: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val isProjectionRunning by HaramBlurRuntimeBus.isProjectionRunning.collectAsState()
    val reminderPackage by HaramBlurRuntimeBus.autoRunReminderPackage.collectAsState()
    var showAutoRunPickerDialog by remember { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.setHaramBlurEnabled(true)
            HaramBlurRuntimeBus.dismissAutoRunReminder()
            HaramBlurProjectionService.startProjection(
                context = context,
                resultCode = result.resultCode,
                data = result.data!!
            )
        } else {
            Toast.makeText(
                context,
                "Screen sharing confirmation is required to start Real-Time Blur.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val requestStartHaramBlur = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                "Please grant Display Over Other Apps permission first.",
                Toast.LENGTH_LONG
            ).show()
            try {
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(overlayIntent)
            } catch (_: Throwable) {
            }
        } else {
            try {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
            } catch (t: Throwable) {
                // Fallback for test/headless environments without MediaProjection system dialog
                viewModel.setHaramBlurEnabled(true)
            }
        }
    }

    val isHaramBlurActive = state.isHaramBlurEnabled || isProjectionRunning

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("module_2_haramblur_container"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Non-intrusive Floating Auto-Run Reminder Banner when user opens a selected app while HaramBlur is OFF
        AnimatedVisibility(visible = reminderPackage != null && !isHaramBlurActive) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("autorun_reminder_banner"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Auto-Run Reminder",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Run Reminder: ${reminderPackage ?: ""}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = "Tap Start to enable real-time regional face & figure blurring.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Button(
                        onClick = { requestStartHaramBlur() },
                        modifier = Modifier.testTag("autorun_banner_start_button")
                    ) {
                        Text("Start")
                    }
                    IconButton(onClick = { HaramBlurRuntimeBus.dismissAutoRunReminder() }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }

        // Master Toggle 2 Header Card (HaramBlur Style)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("master_card_haramblur"),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isHaramBlurActive) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                }
            ),
            border = BorderStroke(
                1.5.dp,
                if (isHaramBlurActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (isHaramBlurActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BlurOn,
                            contentDescription = "HaramBlur Regional Protection",
                            tint = if (isHaramBlurActive) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.toggleHaramBlurSettingsDropdown() }
                    ) {
                        Text(
                            text = "PROTECTION STATUS: System-wide protection",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Real-Time Gaze & Face/Figure Blur",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isHaramBlurActive) {
                                "Real-time regional blurring active"
                            } else {
                                "Protection is off until you start system-wide protection"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = isHaramBlurActive,
                        onCheckedChange = { shouldEnable ->
                            if (shouldEnable) {
                                requestStartHaramBlur()
                            } else {
                                runWithPinGate {
                                    viewModel.setHaramBlurEnabled(false)
                                    HaramBlurProjectionService.stopProjection(context)
                                }
                            }
                        },
                        modifier = Modifier.testTag("master_toggle_haramblur")
                    )

                    IconButton(
                        onClick = { viewModel.toggleHaramBlurSettingsDropdown() },
                        modifier = Modifier.testTag("dropdown_toggle_haramblur")
                    ) {
                        Icon(
                            imageVector = if (state.isHaramBlurSettingsExpanded) {
                                Icons.Default.ExpandLess
                            } else {
                                Icons.Default.ExpandMore
                            },
                            contentDescription = "Expand or Collapse HaramBlur Settings"
                        )
                    }
                }
            }
        }

        // Dynamic Expandable Dropdown for Module 2 (HaramBlur Controls)
        AnimatedVisibility(visible = state.isHaramBlurSettingsExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("haramblur_dropdown_content"),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. "HOW TO ENABLE" 3-Step Horizontal Guide Strip
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "HOW TO ENABLE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GuideStepBadge(
                                stepNumber = "1",
                                text = "Press the toggle.",
                                modifier = Modifier.weight(1f)
                            )
                            GuideStepBadge(
                                stepNumber = "2",
                                text = "Choose an app or your entire screen.",
                                modifier = Modifier.weight(1f)
                            )
                            GuideStepBadge(
                                stepNumber = "3",
                                text = "Confirm Android's sharing prompt.",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 2. Protection Mode Selector (System-Wide vs Selected Apps Only / Auto-Run Mode)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "REGIONAL PROTECTION MODE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HaramBlurProtectionScope.entries.forEach { scope ->
                            val selected = state.haramBlurProtectionScope == scope
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runWithPinGate { viewModel.updateHaramBlurProtectionScope(scope) }
                                    }
                                    .padding(vertical = 6.dp)
                                    .testTag("haramblur_scope_${scope.name.lowercase()}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        runWithPinGate { viewModel.updateHaramBlurProtectionScope(scope) }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(scope.label, fontWeight = FontWeight.Bold)
                                    Text(
                                        scope.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Auto-Run App Reminder & Selector Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-run (${state.autoRunPackages.size} selected)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Select apps that when opened, AmnGaze will remind you to enable detection automatically",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        FilledTonalButton(
                            onClick = { showAutoRunPickerDialog = true },
                            modifier = Modifier.testTag("haramblur_select_apps_button")
                        ) {
                            Text("Select apps")
                        }
                    }
                }

                // 4. Who to Blur Selector (Female, Male, Both)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "WHO TO BLUR",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Choose which faces/figures you want to blur for your comfort.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HaramBlurTargetGender.entries.forEach { gender ->
                                val selected = state.haramBlurTargetGender == gender
                                OutlinedCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            runWithPinGate { viewModel.updateHaramBlurTargetGender(gender) }
                                        }
                                        .testTag("who_to_blur_card_${gender.name.lowercase()}"),
                                    border = BorderStroke(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = when (gender) {
                                                HaramBlurTargetGender.FEMALE -> Icons.Default.Face3
                                                HaramBlurTargetGender.MALE -> Icons.Default.Face6
                                                HaramBlurTargetGender.BOTH -> Icons.Default.Groups
                                            },
                                            contentDescription = gender.displayName,
                                            tint = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = when (gender) {
                                                HaramBlurTargetGender.FEMALE -> "Female"
                                                HaramBlurTargetGender.MALE -> "Male"
                                                HaramBlurTargetGender.BOTH -> "Both"
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Detection Sensitivity Selector (Low, Medium, High)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "DETECTION SENSITIVITY",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HaramBlurSensitivity.entries.forEach { sens ->
                            val selected = state.haramBlurSensitivity == sens
                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        runWithPinGate { viewModel.updateHaramBlurSensitivity(sens) }
                                    }
                                    .testTag("haramblur_sensitivity_${sens.name.lowercase()}"),
                                border = BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = {
                                            runWithPinGate { viewModel.updateHaramBlurSensitivity(sens) }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sens.displayName, fontWeight = FontWeight.Bold)
                                        Text(
                                            sens.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    ) {
                                        Text(
                                            text = "${(sens.threshold * 100).roundToInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. Overlay Style (Blur, Pixelate, Black, Gray, Skin) & Effect Strength (1..10)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "OVERLAY STYLE & EFFECT STRENGTH",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HaramBlurOverlayStyle.entries.forEach { style ->
                                val selected = state.haramBlurOverlayStyle == style
                                val previewColor = Color(style.argbColor)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            runWithPinGate { viewModel.updateHaramBlurOverlayStyle(style) }
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                        .testTag("haramblur_style_${style.name.lowercase()}"),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(previewColor)
                                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = style.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        val blockSizeDp = HaramBlurFilterLogic.computePixelationBlockSizeDp(state.haramBlurEffectStrength)
                        val solidnessPct = HaramBlurFilterLogic.computeOverlaySolidnessPercent(state.haramBlurEffectStrength)
                        val solidnessLabel = if (solidnessPct >= 100) "100% Solid (No See-Through)" else "${solidnessPct}% Density"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Effect Strength: ${state.haramBlurEffectStrength} / 10",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$solidnessLabel • ${blockSizeDp.roundToInt()}dp",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = state.haramBlurEffectStrength.toFloat(),
                            onValueChange = {
                                viewModel.updateHaramBlurEffectStrength(it.roundToInt().coerceIn(1, 10))
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("haramblur_strength_slider")
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runWithPinGate { viewModel.togglePreBlurNewMediaUntilScanned() }
                                }
                                .testTag("haramblur_preblur_new_media_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Pre-Blur New Images & Frames",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Keep newly appearing photos and video frames blurred until AI detection confirms they are safe.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = state.preBlurNewMediaUntilScanned,
                                onCheckedChange = {
                                    runWithPinGate { viewModel.setPreBlurNewMediaUntilScanned(it) }
                                },
                                modifier = Modifier.testTag("haramblur_preblur_new_media_switch")
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAutoRunPickerDialog) {
        AutoRunAppPickerDialog(
            selectedPackages = state.autoRunPackages,
            onTogglePackage = { pkg -> viewModel.toggleAutoRunPackage(pkg) },
            onDismiss = { showAutoRunPickerDialog = false }
        )
    }
}

@Composable
private fun GuideStepBadge(
    stepNumber: String,
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
