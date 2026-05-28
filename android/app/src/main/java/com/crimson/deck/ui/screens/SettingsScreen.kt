package com.crimson.deck.ui.screens

import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import com.crimson.deck.ui.viewmodel.AgentViewModel
import com.crimson.deck.ui.viewmodel.CustomMacro

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit
) {
    var portInput by remember { mutableStateOf(viewModel.serverPort.toString()) }
    var saveMessage by remember { mutableStateOf("") }
    var showMacroCreator by remember { mutableStateOf(false) }
    var macroName by remember { mutableStateOf("") }
    var macroFormula by remember { mutableStateOf(TextFieldValue("")) }
    var isEditing by remember { mutableStateOf(false) }
    var macroDelayMs by remember { mutableStateOf("120") }
    val scope = rememberCoroutineScope()
    val syncLogs = remember { mutableStateListOf<String>() }

    fun addSyncLog(message: String) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val time = sdf.format(java.util.Date())
        syncLogs.add("[$time] $message")
        if (syncLogs.size > 20) {
            syncLogs.removeAt(0)
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val createJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val content = viewModel.gson.toJson(viewModel.customMacros)
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
                saveMessage = "EXPORT SUCCESSFUL!"
            } catch (e: Exception) {
                saveMessage = "EXPORT FAILED: ${e.message}"
            }
        }
    }

    val createTomlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                val content = viewModel.exportToToml(viewModel.customMacros)
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
                saveMessage = "EXPORT SUCCESSFUL!"
            } catch (e: Exception) {
                saveMessage = "EXPORT FAILED: ${e.message}"
            }
        }
    }

    val createYamlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                val content = viewModel.exportToYaml(viewModel.customMacros)
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
                saveMessage = "EXPORT SUCCESSFUL!"
            } catch (e: Exception) {
                saveMessage = "EXPORT FAILED: ${e.message}"
            }
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val filename = uri.lastPathSegment ?: ""
                var format = when {
                    filename.contains(".json", ignoreCase = true) -> "json"
                    filename.contains(".toml", ignoreCase = true) -> "toml"
                    filename.contains(".yaml", ignoreCase = true) || filename.contains(".yml", ignoreCase = true) -> "yaml"
                    else -> ""
                }
                if (format.isEmpty()) {
                    var name = ""
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            name = cursor.getString(nameIndex)
                        }
                    }
                    format = when {
                        name.contains(".json", ignoreCase = true) -> "json"
                        name.contains(".toml", ignoreCase = true) -> "toml"
                        name.contains(".yaml", ignoreCase = true) || name.contains(".yml", ignoreCase = true) -> "yaml"
                        else -> "json"
                    }
                }

                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val success = viewModel.importMacrosFromText(content, format)
                if (success) {
                    saveMessage = "IMPORT SUCCESSFUL!"
                } else {
                    saveMessage = "IMPORT FAILED: INVALID FORMAT"
                }
            } catch (e: Exception) {
                saveMessage = "IMPORT FAILED: ${e.message}"
            }
        }
    }

    // Active color target index state: 0=Primary, 1=Secondary, 2=Background, 3=Panel
    var activeColorTarget by remember { mutableStateOf(0) }

    // Bind local variables directly to reactive viewmodel theme states!
    val neonCrimson = viewModel.themePrimary
    val darkMaroon = viewModel.themeSecondary
    val panelBg = viewModel.themePanel

    val crimsonGradient = Brush.horizontalGradient(
        colors = listOf(neonCrimson, darkMaroon)
    )

    val darkGradient = Brush.verticalGradient(
        colors = listOf(
            viewModel.themeBackground,
            Color(
                red = (viewModel.themeBackground.red * 0.4f),
                green = (viewModel.themeBackground.green * 0.4f),
                blue = (viewModel.themeBackground.blue * 0.4f),
                alpha = viewModel.themeBackground.alpha
            )
        )
    )

    val borderGradient = Brush.verticalGradient(
        colors = listOf(neonCrimson.copy(alpha = 0.35f), Color.Transparent)
    )

    // Preset Cyberpunk Themes Data Structure
    val presets = listOf(
        PresetTheme("CRIMSON PROTOCOL", 0xFFFF0055L, 0xFF8B002AL, 0xFF060509L, 0xE616121DL),
        PresetTheme("TOXIC CYBER", 0xFF00FF66L, 0xFF008033L, 0xFF030805L, 0xE60E1A11L),
        PresetTheme("COBALT FROST", 0xFF00D2FFL, 0xFF0066B3L, 0xFF040A10L, 0xE60C1826L),
        PresetTheme("AMBER MATRIX", 0xFFFFB300L, 0xFFB35900L, 0xFF090604L, 0xE619120CL),
        PresetTheme("OBSIDIAN CYBER", 0xFFD300C5L, 0xFF6A0063L, 0xFF050306L, 0xE6120E15L)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkGradient)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Futuristic Header Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(82.dp)
                            .height(38.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                            .background(panelBg)
                            .border(
                                1.dp,
                                Brush.horizontalGradient(listOf(neonCrimson.copy(alpha = 0.6f), Color.Transparent)),
                                RoundedCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
                            )
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "BACK",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }

                    Text(
                        text = "SETTINGS",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.End
                    )
                }
            }

            item {
                HorizontalDivider(color = Color(0xFF281E2C), thickness = 1.dp)
            }

            // Connection and Navigation Settings Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = panelBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderGradient, RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Workstation Gateway Port Config
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "WORKSTATION GATEWAY PORT",
                                color = neonCrimson,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BasicTextField(
                                    value = portInput,
                                    onValueChange = { portInput = it },
                                    textStyle = TextStyle(
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    cursorBrush = SolidColor(neonCrimson),
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF0F0B14))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 14.dp),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (portInput.isEmpty()) {
                                                Text(
                                                    text = "ENTER PORT",
                                                    color = Color.White.copy(alpha = 0.3f),
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )

                                Box(
                                    modifier = Modifier
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(crimsonGradient)
                                        .clickable {
                                            val port = portInput.toIntOrNull()
                                            if (port != null && port in 1..65535) {
                                                viewModel.saveServerPort(port)
                                                saveMessage = "PORT SAVED SUCCESSFULLY!"
                                            } else {
                                                saveMessage = "INVALID PORT VALUE!"
                                            }
                                        }
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "SAVE",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = Color(0xFF281E2C), thickness = 0.5.dp)

                        // Navigation Mode selector capsules
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "TOUCH CONTROL NAVIGATION MODE",
                                color = neonCrimson,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0F0B14))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val absoluteActive = viewModel.currentMode == 0
                                val relativeActive = viewModel.currentMode == 1

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .background(if (absoluteActive) neonCrimson else Color.Transparent)
                                        .clickable { viewModel.currentMode = 0 },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "DIRECT TOUCH",
                                        color = if (absoluteActive) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .background(if (relativeActive) neonCrimson else Color.Transparent)
                                        .clickable { viewModel.currentMode = 1 },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "TRACKPAD DRIFT",
                                        color = if (relativeActive) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "DOUBLE-TAP ZOOM IN SCALE",
                                color = neonCrimson.copy(alpha = 0.8f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Decrement Button
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1C1322))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                        .clickable {
                                            if (viewModel.doubleTapZoomScale > 1.5f) {
                                                viewModel.saveDoubleTapZoomScale(viewModel.doubleTapZoomScale - 0.5f)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "-",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp
                                    )
                                }

                                // Value Display
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF0F0B14))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = String.format(java.util.Locale.US, "%.1fx Zoom", viewModel.doubleTapZoomScale),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                // Increment Button
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1C1322))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                        .clickable {
                                            if (viewModel.doubleTapZoomScale < 4.0f) {
                                                viewModel.saveDoubleTapZoomScale(viewModel.doubleTapZoomScale + 0.5f)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Screen share visual metrics toggle card
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "SCREEN SHARE VISUAL METRICS",
                                color = neonCrimson,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0F0B14))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val statsOn = viewModel.showStreamStats
                                val statsOff = !viewModel.showStreamStats

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .background(if (statsOn) neonCrimson else Color.Transparent)
                                        .clickable { viewModel.saveShowStreamStats(true) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "SHOW STATS",
                                        color = if (statsOn) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .background(if (statsOff) neonCrimson else Color.Transparent)
                                        .clickable { viewModel.saveShowStreamStats(false) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "HIDE STATS",
                                        color = if (statsOff) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = Color(0xFF281E2C), thickness = 0.5.dp)

                        // Keyboard Input Mode selector capsules
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "KEYBOARD INPUT CONFIGURATION",
                                color = neonCrimson,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0F0B14))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val bufferedActive = viewModel.currentKeyboardMode == 0
                                val directActive = viewModel.currentKeyboardMode == 1

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .background(if (bufferedActive) neonCrimson else Color.Transparent)
                                        .clickable { viewModel.saveKeyboardMode(0) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "BUFFERED TEXT BOX",
                                        color = if (bufferedActive) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .background(if (directActive) neonCrimson else Color.Transparent)
                                        .clickable { viewModel.saveKeyboardMode(1) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "DIRECT RAW KEYBOARD",
                                        color = if (directActive) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "REPEAT KEYSTROKE DELAY (ALL ALLOWED KEYS)",
                                color = neonCrimson.copy(alpha = 0.8f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Decrement Button
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1C1322))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                        .clickable {
                                            if (viewModel.repeatKeyDelay > 10) {
                                                viewModel.saveRepeatKeyDelay(viewModel.repeatKeyDelay - 5)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "-",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp
                                    )
                                }

                                // Value Display
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF0F0B14))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${viewModel.repeatKeyDelay} ms",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                // Increment Button
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1C1322))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                        .clickable {
                                            if (viewModel.repeatKeyDelay < 250) {
                                                viewModel.saveRepeatKeyDelay(viewModel.repeatKeyDelay + 5)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "ALLOWED REPEAT KEYS",
                                color = neonCrimson.copy(alpha = 0.8f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )

                            // Row 1 of allowed repeating keys
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val keys = listOf("BKSP", "TAB", "ENT", "SPC")
                                keys.forEach { key ->
                                    val active = viewModel.allowedRepeatKeys.contains(key)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (active) neonCrimson else Color(0xFF0F0B14))
                                            .border(
                                                1.dp,
                                                if (active) Color.Transparent else neonCrimson.copy(alpha = 0.2f),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .clickable { viewModel.toggleAllowedRepeatKey(key) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key,
                                            color = if (active) Color.Black else Color(0xFFC299A6),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }

                            // Row 2 of allowed repeating keys
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val keys = listOf("▲", "▼", "◀", "▶")
                                keys.forEach { key ->
                                    val active = viewModel.allowedRepeatKeys.contains(key)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (active) neonCrimson else Color(0xFF0F0B14))
                                            .border(
                                                1.dp,
                                                if (active) Color.Transparent else neonCrimson.copy(alpha = 0.2f),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .clickable { viewModel.toggleAllowedRepeatKey(key) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key,
                                            color = if (active) Color.Black else Color(0xFFC299A6),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // STREAMING PERFORMANCE & LATENCY CONTROL SECTION
            item {
                Text(
                    text = "STREAMING PERFORMANCE & LATENCY CONTROL",
                    color = neonCrimson,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = panelBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            neonCrimson.copy(alpha = 0.15f),
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Dynamic Capture Pacing
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "DYNAMIC CAPTURE PACING (BACKPRESSURE)",
                                color = Color(0xFFC299A6),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0F0B14))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val backpressureOn = viewModel.useBackpressure
                                val backpressureOff = !viewModel.useBackpressure

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .background(if (backpressureOn) neonCrimson else Color.Transparent)
                                        .clickable { viewModel.saveUseBackpressure(true) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "ADAPTIVE PACING",
                                        color = if (backpressureOn) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .background(if (backpressureOff) neonCrimson else Color.Transparent)
                                        .clickable { viewModel.saveUseBackpressure(false) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "FIXED 60 FPS",
                                        color = if (backpressureOff) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // PRESET CYBERPUNK THEMES SECTION
            item {
                Text(
                    text = "PRESET OPERATIONAL THEMES",
                    color = Color(0xFF9E92AC),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            // Theme Preset selections
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    presets.forEach { preset ->
                        val isSelected = viewModel.themePrimary == Color(preset.primary)
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = panelBg),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isSelected) neonCrimson else neonCrimson.copy(alpha = 0.15f),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    viewModel.applyTheme(preset.primary, preset.secondary, preset.background, preset.panel)
                                    saveMessage = "THEME APPLIED: ${preset.name}!"
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = preset.name,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp
                                )

                                // Color Preview Capsules Row
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ColorDot(preset.primary)
                                    ColorDot(preset.secondary)
                                    ColorDot(preset.background)
                                    ColorDot(preset.panel)
                                }
                            }
                        }
                    }
                }
            }

            // DYNAMIC INTERACTIVE CUSTOM COLOR PICKER SECTION
            item {
                Text(
                    text = "INTERACTIVE TACTICAL COLOR PICKER",
                    color = Color(0xFF9E92AC),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = panelBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderGradient, RoundedCornerShape(24.dp))
                ) {
                    var activeDragTarget by remember { mutableStateOf<Int?>(null) }

                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "DRAG TARGETS: P (PRIMARY) | S (SECONDARY) | B (BACKGROUND) | PL (PANEL)",
                            color = neonCrimson,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )

                        val rainbowColors = remember {
                            listOf(
                                Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                            )
                        }
                        val rainbowBrush = remember { Brush.horizontalGradient(rainbowColors) }
                        val blackBrush = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black)) }

                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: continue
                                            
                                            if (change.pressed) {
                                                val touchX = change.position.x
                                                val touchY = change.position.y
                                                val w = size.width.toFloat()
                                                val h = size.height.toFloat()

                                                if (activeDragTarget == null) {
                                                    var closestTarget: Int? = null
                                                    var minDist = Float.MAX_VALUE
                                                    
                                                    val currentColors = listOf(
                                                        viewModel.themePrimary,
                                                        viewModel.themeSecondary,
                                                        viewModel.themeBackground,
                                                        viewModel.themePanel
                                                    )
                                                    
                                                    for (i in 0..3) {
                                                        val hv = colorToHueValue(currentColors[i])
                                                        val targetX = (hv.first / 360f) * w
                                                        val targetY = (1.0f - hv.second) * h
                                                        
                                                        val dist = kotlin.math.hypot(touchX - targetX, touchY - targetY)
                                                        if (dist < minDist && dist < 120f) { // ~40.dp
                                                            minDist = dist
                                                            closestTarget = i
                                                        }
                                                    }
                                                    
                                                    if (closestTarget != null) {
                                                        activeDragTarget = closestTarget
                                                        activeColorTarget = closestTarget
                                                    } else {
                                                        // Jump currently active target to the tapped point
                                                        activeDragTarget = activeColorTarget
                                                    }
                                                }
                                                
                                                val lockedTarget = activeDragTarget
                                                if (lockedTarget != null) {
                                                    change.consume()
                                                    val newHue = (touchX / w).coerceIn(0f, 1f) * 360f
                                                    val newVal = 1.0f - (touchY / h).coerceIn(0f, 1f)
                                                    
                                                    val intColor = android.graphics.Color.HSVToColor(floatArrayOf(newHue, 1f, newVal))
                                                    val longColor = intColor.toLong() and 0xFFFFFFFFL
                                                    
                                                    val p = if (lockedTarget == 0) longColor else viewModel.themePrimary.toArgb().toLong() and 0xFFFFFFFFL
                                                    val sec = if (lockedTarget == 1) longColor else viewModel.themeSecondary.toArgb().toLong() and 0xFFFFFFFFL
                                                    val b = if (lockedTarget == 2) longColor else viewModel.themeBackground.toArgb().toLong() and 0xFFFFFFFFL
                                                    val pan = if (lockedTarget == 3) longColor else viewModel.themePanel.toArgb().toLong() and 0xFFFFFFFFL
                                                    
                                                    viewModel.applyTheme(p, sec, b, pan)
                                                }
                                            } else {
                                                activeDragTarget = null
                                            }
                                        }
                                    }
                                }
                        ) {
                            drawRect(brush = rainbowBrush)
                            drawRect(brush = blackBrush)

                            val currentColors = listOf(
                                viewModel.themePrimary,
                                viewModel.themeSecondary,
                                viewModel.themeBackground,
                                viewModel.themePanel
                            )
                            val labels = listOf("P", "S", "B", "PL")
                            val w = size.width
                            val h = size.height

                            for (i in 0..3) {
                                val targetColor = currentColors[i]
                                val label = labels[i]
                                val hv = colorToHueValue(targetColor)
                                val cx = (hv.first / 360f) * w
                                val cy = (1.0f - hv.second) * h

                                // Outer ring highlight for selection
                                val isSelected = activeColorTarget == i
                                val outerRadius = if (isSelected) 16.dp.toPx() else 13.dp.toPx()
                                val strokeWidth = if (isSelected) 3.dp.toPx() else 1.5.dp.toPx()
                                val strokeColor = if (isSelected) Color.White else Color.Black.copy(alpha = 0.6f)

                                // Shadow/Outline
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.4f),
                                    radius = outerRadius + 2.dp.toPx(),
                                    center = Offset(cx, cy)
                                )

                                // Filled colored core
                                drawCircle(
                                    color = targetColor,
                                    radius = outerRadius,
                                    center = Offset(cx, cy)
                                )

                                // White/Black outer border ring
                                drawCircle(
                                    color = strokeColor,
                                    radius = outerRadius,
                                    center = Offset(cx, cy),
                                    style = Stroke(width = strokeWidth)
                                )

                                // Render dynamic label text directly on canvas native drawing context!
                                val textPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    textSize = 10.dp.toPx()
                                    isFakeBoldText = true
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                val textPaintStroke = android.graphics.Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 10.dp.toPx()
                                    isFakeBoldText = true
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    style = android.graphics.Paint.Style.STROKE
                                    setStrokeWidth(2.dp.toPx())
                                }

                                val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                                drawContext.canvas.nativeCanvas.drawText(label, cx, textY, textPaintStroke)
                                drawContext.canvas.nativeCanvas.drawText(label, cx, textY, textPaint)
                            }
                        }
                    }
                }
            }

            // COLOR POINTS DETAILS LIST (DYNAMIC CARDS & COLOR DETAILS BELOW THE PICKER)
            item {
                Text(
                    text = "ACTIVE THEME SYSTEM PARAMETERS",
                    color = Color(0xFF9E92AC),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val colorTargets = listOf(
                        Triple("PRIMARY COLOR (Neon Alerts & High-Visibility Borders)", viewModel.themePrimary, 0),
                        Triple("SECONDARY COLOR (Card Gradients & Modifier Buttons)", viewModel.themeSecondary, 1),
                        Triple("BACKGROUND COLOR (Master Obsidian Surfaces)", viewModel.themeBackground, 2),
                        Triple("PANEL COLOR (Translucent Keyboards & Text Fields)", viewModel.themePanel, 3)
                    )

                    colorTargets.forEach { (label, color, targetIndex) ->
                        val isTargetActive = activeColorTarget == targetIndex
                        val (hex, rgb) = getHexAndRgb(color)

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = panelBg),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isTargetActive) neonCrimson else neonCrimson.copy(alpha = 0.15f),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable { activeColorTarget = targetIndex }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Dynamic color preview dot
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color)
                                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                )

                                // Color Info details block
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        color = if (isTargetActive) Color.White else Color(0xFFD4C2D0),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = "HEX: $hex",
                                            color = Color(0xFF9E92AC),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "RGB: $rgb",
                                            color = Color(0xFF9E92AC),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                // Selection Active Badge
                                if (isTargetActive) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(neonCrimson.copy(alpha = 0.15f))
                                            .border(1.dp, neonCrimson, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "ACTIVE",
                                            color = neonCrimson,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // CUSTOM KEYBOARD MACROS CONSOLE
            item {
                Text(
                    text = "CUSTOM REMOTE KEYBOARD MACROS",
                    color = Color(0xFF9E92AC),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = panelBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderGradient, RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "MACROS CONSOLE & SEQUENCES",
                            color = neonCrimson,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )

                        if (viewModel.customMacros.isEmpty()) {
                            Text(
                                text = "No macros configured. Add a macro below to automate keyboard sequences on your host.",
                                color = Color(0xFF6B5B75),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 480.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                viewModel.customMacros.forEach { macro ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0F0B14), RoundedCornerShape(12.dp))
                                            .border(1.dp, neonCrimson.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = macro.name.uppercase(),
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.5.sp
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${macro.formula} (${macro.delayMs}ms)",
                                                color = neonCrimson,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp
                                            )
                                        }

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Edit macro button
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF0F1E14))
                                                    .border(1.dp, Color(0xFF00FF66).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        isEditing = true
                                                        macroName = macro.name
                                                        macroFormula = TextFieldValue(macro.formula, TextRange(macro.formula.length))
                                                        macroDelayMs = macro.delayMs.toString()
                                                        showMacroCreator = true
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "✎",
                                                    color = Color(0xFF00FF66),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            }

                                            // Delete macro button
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF220C12))
                                                    .border(1.dp, Color(0xFFE53935).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        viewModel.deleteMacro(macro.name)
                                                        saveMessage = "MACRO DELETED SUCCESSFULLY!"
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "✕",
                                                    color = Color(0xFFE53935),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Trigger to open the pop-up macro editor
                        Button(
                            onClick = {
                                isEditing = false
                                macroName = ""
                                macroFormula = TextFieldValue("")
                                macroDelayMs = "120"
                                showMacroCreator = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .background(crimsonGradient, RoundedCornerShape(12.dp))
                        ) {
                            Text(
                                text = "ADD NEW CUSTOM MACRO",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                        }
                    }
                }
            }

            // MACROS EXPORT & IMPORT / REMOTE SERVER SYNC
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = panelBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderGradient, RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "MACROS BACKUP & SERVER SYNC",
                            color = neonCrimson,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )

                        Text(
                            text = "Backup custom macros locally in multiple formats or sync directly to/from your workstation.",
                            color = Color(0xFF6B5B75),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "LOCAL DEVICE BACKUPS",
                            color = Color(0xFF9E92AC),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // JSON Export
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F0B14))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        createJsonLauncher.launch("macros.json")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("EXPORT JSON", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }

                            // TOML Export
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F0B14))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        createTomlLauncher.launch("macros.toml")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("EXPORT TOML", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }

                            // YAML Export
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F0B14))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        createYamlLauncher.launch("macros.yaml")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("EXPORT YAML", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Local Import Button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF160E1A))
                                .border(1.dp, Color(0xFF9E92AC).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .clickable {
                                    openDocLauncher.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("IMPORT FROM LOCAL FILE", color = Color(0xFF9E92AC), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "WORKSTATION SYNC PORTAL",
                            color = Color(0xFF9E92AC),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Sync Export to Server
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(crimsonGradient)
                                    .clickable {
                                        scope.launch {
                                            syncLogs.clear()
                                            addSyncLog("STARTING EXPORT SYNC PROCESS...")
                                            val originallyConnected = viewModel.isConnected
                                            if (!originallyConnected) {
                                                addSyncLog("WORKSTATION OFFLINE. TARGET: ${viewModel.serverHost}:${viewModel.serverPort}")
                                                addSyncLog("ESTABLISHING TEMPORARY LINK...")
                                                viewModel.connectToWorkstation(viewModel.serverHost)
                                                var elapsed = 0
                                                while (!viewModel.isConnected && elapsed < 50) {
                                                    delay(100L)
                                                    elapsed++
                                                }
                                                if (!viewModel.isConnected) {
                                                    addSyncLog("CRITICAL: CONNECTION TIMEOUT!")
                                                    saveMessage = "CONNECTION TIMEOUT!"
                                                    return@launch
                                                }
                                                addSyncLog("LINK SECURED SUCCESSFULLY.")
                                            } else {
                                                addSyncLog("LINK ACTIVE. TARGET: ${viewModel.serverHost}:${viewModel.serverPort}")
                                            }
                                            
                                            addSyncLog("SERIALIZING CUSTOM MACROS TO JSON, TOML, AND YAML...")
                                            saveMessage = "SYNCHRONIZING EXPORT..."
                                            viewModel.exportMacrosToServer(
                                                onSuccess = {
                                                    addSyncLog("TRANSMITTING PAYLOADS TO WORKSTATION HOST...")
                                                    addSyncLog("SUCCESS: SERVER SAVED backups inside './export/' directory!")
                                                    saveMessage = "SERVER SYNC EXPORT SUCCESSFUL!"
                                                    if (!originallyConnected) {
                                                        addSyncLog("TEARING DOWN TEMPORARY LINK...")
                                                        viewModel.disconnect()
                                                        addSyncLog("LINK DISCONNECTED CLEANLY.")
                                                    }
                                                },
                                                onError = { err ->
                                                    addSyncLog("ERROR: API TRANSMISSION FAILED -> $err")
                                                    saveMessage = "SYNC FAILED: $err"
                                                    if (!originallyConnected) {
                                                        viewModel.disconnect()
                                                        addSyncLog("LINK TEARDOWN COMPLETED.")
                                                    }
                                                }
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("SYNC TO SERVER", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }

                            // Sync Import from Server
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF220C12))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch {
                                            syncLogs.clear()
                                            addSyncLog("STARTING IMPORT SYNC PROCESS...")
                                            val originallyConnected = viewModel.isConnected
                                            if (!originallyConnected) {
                                                addSyncLog("WORKSTATION OFFLINE. TARGET: ${viewModel.serverHost}:${viewModel.serverPort}")
                                                addSyncLog("ESTABLISHING TEMPORARY LINK...")
                                                viewModel.connectToWorkstation(viewModel.serverHost)
                                                var elapsed = 0
                                                while (!viewModel.isConnected && elapsed < 50) {
                                                    delay(100L)
                                                    elapsed++
                                                }
                                                if (!viewModel.isConnected) {
                                                    addSyncLog("CRITICAL: CONNECTION TIMEOUT!")
                                                    saveMessage = "CONNECTION TIMEOUT!"
                                                    return@launch
                                                }
                                                addSyncLog("LINK SECURED SUCCESSFULLY.")
                                            } else {
                                                addSyncLog("LINK ACTIVE. TARGET: ${viewModel.serverHost}:${viewModel.serverPort}")
                                            }
                                            
                                            addSyncLog("REQUESTING BACKUP PAYLOAD FROM WORKSTATION HOST...")
                                            saveMessage = "SYNCHRONIZING IMPORT..."
                                            viewModel.importMacrosFromServer(
                                                onSuccess = {
                                                    addSyncLog("RECEIVING payload from remote './export/macros.json'...")
                                                    addSyncLog("MERGING and updating local custom macros list...")
                                                    addSyncLog("SUCCESS: SERVER SYNC IMPORT COMPLETED!")
                                                    saveMessage = "SERVER SYNC IMPORT SUCCESSFUL!"
                                                    if (!originallyConnected) {
                                                        addSyncLog("TEARING DOWN TEMPORARY LINK...")
                                                        viewModel.disconnect()
                                                        addSyncLog("LINK DISCONNECTED CLEANLY.")
                                                    }
                                                },
                                                onError = { err ->
                                                    addSyncLog("ERROR: API RETRIEVAL FAILED -> $err")
                                                    saveMessage = "SYNC FAILED: $err"
                                                    if (!originallyConnected) {
                                                        viewModel.disconnect()
                                                        addSyncLog("LINK TEARDOWN COMPLETED.")
                                                    }
                                                }
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("SYNC FROM SERVER", color = neonCrimson, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }

                        if (syncLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "SYNC LOG ENGINE OUTPUT",
                                color = Color(0xFF6B5B75),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF060509))
                                    .border(1.dp, neonCrimson.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                syncLogs.forEach { log ->
                                    Text(
                                        text = log,
                                        color = if (log.contains("FAILED") || log.contains("TIMEOUT") || log.contains("ERROR")) Color(0xFFE53935) else if (log.contains("SUCCESS")) Color(0xFF00FF66) else Color(0xFF9E92AC),
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontSize = 8.5.sp,
                                        lineHeight = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Maintenance and Device Info
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = panelBg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "CACHE & DEVICE MAINTENANCE",
                            color = neonCrimson,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )

                        Button(
                            onClick = {
                                viewModel.clearHistory()
                                saveMessage = "DEVICE HISTORY CLEARED!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF19101F)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .border(1.dp, Color(0xFF4C305F), RoundedCornerShape(12.dp))
                        ) {
                            Text(
                                text = "CLEAR DISCOVERED HISTORY",
                                color = Color(0xFFD4C2D0),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            // ─── About ───────────────────────────────────────────────────────

            item {
                Spacer(modifier = Modifier.height(12.dp))

                // Gradient divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    neonCrimson.copy(alpha = 0.4f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Spacer(modifier = Modifier.height(20.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ABOUT",
                        color = neonCrimson.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "CRIMSON DECK",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Remote Desktop Companion",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Crimson Deck turns your Android device into a low-latency remote control for your Linux workstation. " +
                               "It streams a live H.264-encoded view of your desktop over your local network or Tailscale VPN, " +
                               "and relays touch gestures as real mouse clicks, drags, and scrolls. " +
                               "Keyboard input, modifier keys, and custom macro sequences are injected directly into the X11 session via xdotool.\n\n" +
                               "The server is built on a Rust screen-capture engine using X11 MIT-SHM for zero-copy frame grabs, " +
                               "paired with a Go signaling gateway that handles WebSocket transport and i3 IPC workspace control. " +
                               "Clipboard sync, file transfer, and dynamic theme customisation are all included out of the box.",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        letterSpacing = 0.2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    // Version — centered at the very bottom of the settings page
                    Text(
                        text = "v2.1",
                        color = neonCrimson.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }        // POPUP CREATOR OVERLAY FLOATED DOWN WITH 24.DP CORNERS ON ALL SIDES
        if (showMacroCreator) {
            fun appendKey(key: String) {
                val currentText = macroFormula.text
                val selectionStart = macroFormula.selection.start.coerceIn(0, currentText.length)
                val selectionEnd = macroFormula.selection.end.coerceIn(0, currentText.length)
                
                val prefix = currentText.substring(0, selectionStart)
                val suffix = currentText.substring(selectionEnd)
                
                val updatedText = prefix + key + suffix
                val newCursorPos = selectionStart + key.length
                
                macroFormula = TextFieldValue(
                    text = updatedText,
                    selection = TextRange(newCursorPos)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable { showMacroCreator = false }
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = panelBg),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 110.dp, start = 16.dp, end = 16.dp) // Floated down and padded
                        .fillMaxWidth()
                        .height(320.dp) // Set stable height fitting all elements without scrolling
                        .clip(RoundedCornerShape(24.dp)) // Clip content to ensure perfect circular edges on all sides
                        .border(
                            1.5.dp,
                            Brush.verticalGradient(
                                listOf(
                                    neonCrimson.copy(alpha = 0.6f),
                                    neonCrimson.copy(alpha = 0.25f)
                                )
                            ),
                            RoundedCornerShape(24.dp) // Circular edges on all sides
                        )
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp), // Removed verticalScroll to show everything at once
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isEditing) "EDIT MACRO" else "MACRO BUILDER CONSOLE",
                                color = neonCrimson,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )

                            Text(
                                text = "CLOSE ✕",
                                color = Color.White.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.clickable { showMacroCreator = false }
                            )
                        }

                         Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top // Align elements to top for mixed heights
                        ) {
                            Column(modifier = Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("MACRO NAME", color = Color(0xFF9E92AC), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                BasicTextField(
                                    value = macroName,
                                    onValueChange = { macroName = it },
                                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F0B14))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                                            if (macroName.isEmpty()) Text("e.g. My Macro", color = Color.Gray.copy(alpha = 0.6f), fontSize = 11.sp)
                                            inner()
                                        }
                                    }
                                )
                            }

                            Column(modifier = Modifier.weight(1.8f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("SEQUENCE FORMULA", color = Color(0xFF9E92AC), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                BasicTextField(
                                    value = macroFormula,
                                    onValueChange = { macroFormula = it },
                                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                    singleLine = false, // Multi-line text area
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp) // Beautiful multi-line height
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F0B14))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.TopStart, modifier = Modifier.fillMaxSize()) { // Aligned to TopStart for text area
                                            if (macroFormula.text.isEmpty()) Text("ctrl+alt+t", color = Color.Gray.copy(alpha = 0.6f), fontSize = 11.sp)
                                            inner()
                                        }
                                    }
                                )
                            }

                            Column(modifier = Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("DELAY (MS)", color = Color(0xFF9E92AC), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                BasicTextField(
                                    value = macroDelayMs,
                                    onValueChange = { macroDelayMs = it.filter { char -> char.isDigit() } },
                                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F0B14))
                                        .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                                            inner()
                                        }
                                    }
                                )
                            }
                        }

                        Text(
                            text = "TAP KEYPAD BUTTONS TO CONSTRUCT FORMULA SEQUENCE",
                            color = Color(0xFF6B5B75),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MacroKeyButton("CTRL", Modifier.weight(1f)) { appendKey("ctrl") }
                            MacroKeyButton("ALT", Modifier.weight(1f)) { appendKey("alt") }
                            MacroKeyButton("SHFT", Modifier.weight(1f)) { appendKey("shft") }
                            MacroKeyButton("SUPR", Modifier.weight(1f)) { appendKey("supr") }
                            MacroKeyButton("+", Modifier.weight(0.8f), backgroundColor = neonCrimson, contentColor = Color.Black) { appendKey("+") }
                            MacroKeyButton(",", Modifier.weight(0.8f), backgroundColor = darkMaroon, contentColor = Color.White) { appendKey(",") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MacroKeyButton("ESC", Modifier.weight(1f)) { appendKey("esc") }
                            MacroKeyButton("TAB", Modifier.weight(1f)) { appendKey("tab") }
                            MacroKeyButton("BKSP", Modifier.weight(1f)) { appendKey("bksp") }
                            MacroKeyButton("ENT", Modifier.weight(1f)) { appendKey("enter") }
                            MacroKeyButton("SPC", Modifier.weight(1f)) { appendKey("space") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MacroKeyButton("▲", Modifier.weight(1f)) { appendKey("up") }
                            MacroKeyButton("▼", Modifier.weight(1f)) { appendKey("down") }
                            MacroKeyButton("◀", Modifier.weight(1f)) { appendKey("left") }
                            MacroKeyButton("▶", Modifier.weight(1f)) { appendKey("right") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(38.dp)
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF220C12))
                                    .border(1.dp, Color(0xFFE53935).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .clickable { macroFormula = TextFieldValue("") },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("CLEAR FORMULA", color = Color(0xFFE53935), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Box(
                                modifier = Modifier
                                    .height(38.dp)
                                    .weight(1f) // Equal weight and size to CLEAR FORMULA button down to the pixel!
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(crimsonGradient)
                                    .clickable {
                                        if (macroName.trim().isNotEmpty() && macroFormula.text.trim().isNotEmpty()) {
                                            val parsedDelay = macroDelayMs.toIntOrNull() ?: 120
                                            viewModel.saveMacro(
                                                CustomMacro(
                                                    name = macroName.trim(),
                                                    formula = macroFormula.text.trim(),
                                                    delayMs = parsedDelay
                                                )
                                            )
                                            showMacroCreator = false
                                            saveMessage = "MACRO SAVED SUCCESSFULLY!"
                                        } else {
                                            saveMessage = "NAME AND FORMULA REQUIRED!"
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isEditing) "EDIT MACRO" else "SAVE MACRO",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun ColorDot(hex: Long) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(hex))
            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
    )
}

// Custom hex and rgb string details extractor (masterpiece)
fun getHexAndRgb(color: Color): Pair<String, String> {
    val argb = color.toArgb()
    val hex = String.format("#%08X", argb)
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val rgb = "rgb($r, $g, $b)"
    return Pair(hex, rgb)
}

// Preset Theme Helper Data Class
data class PresetTheme(
    val name: String,
    val primary: Long,
    val secondary: Long,
    val background: Long,
    val panel: Long
)

fun colorToHueValue(color: Color): Pair<Float, Float> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return Pair(hsv[0], hsv[2])
}

@Composable
fun MacroKeyButton(
    label: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFF100C14),
    contentColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp
        )
    }
}
