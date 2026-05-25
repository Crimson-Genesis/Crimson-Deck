package com.crimson.deck.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.deck.ui.viewmodel.AgentViewModel
import com.crimson.deck.ui.viewmodel.DiscoveredWorkstation
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: AgentViewModel,
    onNavigateToStream: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var ipInput by remember { mutableStateOf(viewModel.serverHost) }

    val context = LocalContext.current



    val uploadFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.pendingUploadUris = uris
            viewModel.isPendingUploadDirectory = false
            viewModel.isFileSharingActive = true
            viewModel.fileSharingMode = 0
            viewModel.fetchRemoteDirectory(null)
        }
    }

    val uploadFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.pendingUploadUris = listOf(uri)
            viewModel.isPendingUploadDirectory = true
            viewModel.isFileSharingActive = true
            viewModel.fileSharingMode = 0
            viewModel.fetchRemoteDirectory(null)
        }
    }

    val downloadFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.downloadSelectedHostItems(
                targetHostPaths = viewModel.remoteSelectedItems.toList(),
                localFolderUri = uri,
                onSuccess = {
                    android.widget.Toast.makeText(context, "DOWNLOAD SUCCESSFUL", android.widget.Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    android.widget.Toast.makeText(context, "DOWNLOAD FAILED: $err", android.widget.Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkGradient)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Brand Header Section
            item {
                Spacer(modifier = Modifier.height(20.dp))
                // Premium Static Crimson Brand Icon
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF140508)) // Ultra dark crimson panel background
                        .border(1.dp, neonCrimson.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Internal soft static crimson glow
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        neonCrimson.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // 2D Monitor-Phone Link Canvas (Static and tilted towards the monitor)
                    Canvas(
                        modifier = Modifier.size(60.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        
                        // Draw base shadow
                        drawOval(
                            color = Color(0xFF0D0306),
                            topLeft = Offset(w * 0.15f, h * 0.82f),
                            size = Size(w * 0.7f, h * 0.08f)
                        )
                        
                        // Draw monitor stand neck
                        val standPath = Path().apply {
                            moveTo(w * 0.44f, h * 0.62f)
                            lineTo(w * 0.56f, h * 0.62f)
                            lineTo(w * 0.60f, h * 0.78f)
                            lineTo(w * 0.40f, h * 0.78f)
                            close()
                        }
                        drawPath(path = standPath, color = Color(0xFF210815))
                        
                        // Draw stand base plate
                        drawRoundRect(
                            color = Color(0xFFFF2E56),
                            topLeft = Offset(w * 0.35f, h * 0.76f),
                            size = Size(w * 0.3f, h * 0.05f),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                        
                        // Draw monitor bezel frame
                        val frameWidth = w * 0.52f
                        val frameHeight = h * 0.42f
                        val frameLeft = w * 0.28f
                        val frameTop = h * 0.22f
                        drawRoundRect(
                            color = Color(0xFF150508),
                            topLeft = Offset(frameLeft, frameTop),
                            size = Size(frameWidth, frameHeight),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                        
                        // Draw inner screen
                        val screenLeft = frameLeft + 3.dp.toPx()
                        val screenTop = frameTop + 3.dp.toPx()
                        val screenWidth = frameWidth - 6.dp.toPx()
                        val screenHeight = frameHeight - 10.dp.toPx()
                        drawRect(
                            color = Color(0xFF8A0A28),
                            topLeft = Offset(screenLeft, screenTop),
                            size = Size(screenWidth, screenHeight)
                        )
                        
                        // Draw diagonal screen reflection
                        val reflectionPath = Path().apply {
                            moveTo(screenLeft + screenWidth * 0.35f, screenTop + screenHeight)
                            lineTo(screenLeft + screenWidth, screenTop + screenHeight * 0.2f)
                            lineTo(screenLeft + screenWidth, screenTop + screenHeight)
                            close()
                        }
                        drawPath(path = reflectionPath, color = Color(0xFFB2123C))
                        
                        // Draw bottom thick red bezel
                        drawRect(
                            color = Color(0xFFFF2E56),
                            topLeft = Offset(frameLeft, frameTop + frameHeight - 7.dp.toPx()),
                            size = Size(frameWidth, 7.dp.toPx())
                        )
                        
                        // Draw webcam block on top
                        drawRoundRect(
                            color = Color(0xFFFF2E56),
                            topLeft = Offset(w * 0.47f, h * 0.18f),
                            size = Size(w * 0.08f, h * 0.04f),
                            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                        )
                        
                        // Draw status power dot
                        drawCircle(
                            color = Color(0xFF150508),
                            radius = 1.5.dp.toPx(),
                            center = Offset(frameLeft + frameWidth / 2f, frameTop + frameHeight - 3.5.dp.toPx())
                        )
                        
                        // Draw Smartphone in foreground (Tilted clockwise: pointing towards computer!)
                        // Wrap in transform to rotate around pivot (w * 0.32f, h * 0.62f) by 24 degrees
                        withTransform({
                            rotate(24f, pivot = Offset(w * 0.32f, h * 0.62f))
                        }) {
                            val phoneLeft = w * 0.22f
                            val phoneTop = h * 0.46f // Moved down from 0.42f to 0.46f
                            val phoneWidth = w * 0.20f
                            val phoneHeight = h * 0.40f
                            
                            // Phone body fill and border
                            drawRoundRect(
                                color = Color(0xFFE60026),
                                topLeft = Offset(phoneLeft, phoneTop),
                                size = Size(phoneWidth, phoneHeight),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                            drawRoundRect(
                                color = Color(0xFF150508),
                                topLeft = Offset(phoneLeft, phoneTop),
                                size = Size(phoneWidth, phoneHeight),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            
                            // Phone speaker notch
                            drawLine(
                                color = Color.White,
                                start = Offset(phoneLeft + phoneWidth * 0.35f, phoneTop + 2.dp.toPx()),
                                end = Offset(phoneLeft + phoneWidth * 0.65f, phoneTop + 2.dp.toPx()),
                                strokeWidth = 1.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            
                            // Wi-Fi symbol (Even smaller Wi-Fi symbol)
                            val wifiCenter = Offset(phoneLeft + phoneWidth / 2f, phoneTop + phoneHeight * 0.68f)
                            drawCircle(
                                color = Color.White,
                                radius = 0.6.dp.toPx(), // Even smaller center dot from 1.0 to 0.6
                                center = wifiCenter
                            )
                            
                            // Inner Arch (Even smaller)
                            drawArc(
                                color = Color.White,
                                startAngle = 210f,
                                sweepAngle = 120f,
                                useCenter = false,
                                topLeft = Offset(wifiCenter.x - 2.25.dp.toPx(), wifiCenter.y - 2.25.dp.toPx()), // Even smaller from 3.5 to 2.25
                                size = Size(4.5.dp.toPx(), 4.5.dp.toPx()), // Even smaller from 7 to 4.5
                                style = Stroke(width = 1.0.dp.toPx(), cap = StrokeCap.Round) // Thinner stroke from 1.5 to 1.0
                             )
                             
                            // Outer Arch (Even smaller)
                            drawArc(
                                color = Color.White,
                                startAngle = 210f,
                                sweepAngle = 120f,
                                useCenter = false,
                                topLeft = Offset(wifiCenter.x - 4.5.dp.toPx(), wifiCenter.y - 4.5.dp.toPx()), // Even smaller from 6.5 to 4.5
                                size = Size(9.dp.toPx(), 9.dp.toPx()), // Even smaller from 13 to 9
                                style = Stroke(width = 1.0.dp.toPx(), cap = StrokeCap.Round) // Thinner stroke from 1.5 to 1.0
                            )
                            
                            // Emitters at the top (Smaller emitting signal wave rays & closer to phone top at 46)
                            val emitterColor = Color.White
                            val emitterStroke = 2.dp.toPx()
                            
                            // Ray 1 (far-left)
                            drawLine(
                                color = emitterColor,
                                start = Offset(phoneLeft + phoneWidth * 0.1f, phoneTop - 5.dp.toPx()),
                                end = Offset(phoneLeft + phoneWidth * 0.25f, phoneTop - 2.dp.toPx()),
                                strokeWidth = emitterStroke,
                                cap = StrokeCap.Round
                            )
                            // Ray 2 (left-mid)
                            drawLine(
                                color = emitterColor,
                                start = Offset(phoneLeft + phoneWidth * 0.3f, phoneTop - 7.dp.toPx()),
                                end = Offset(phoneLeft + phoneWidth * 0.4f, phoneTop - 3.dp.toPx()),
                                strokeWidth = emitterStroke,
                                cap = StrokeCap.Round
                            )
                            // Ray 3 (center)
                            drawLine(
                                color = emitterColor,
                                start = Offset(phoneLeft + phoneWidth * 0.5f, phoneTop - 8.dp.toPx()),
                                end = Offset(phoneLeft + phoneWidth * 0.5f, phoneTop - 4.dp.toPx()),
                                strokeWidth = emitterStroke,
                                cap = StrokeCap.Round
                            )
                            // Ray 4 (right-mid)
                            drawLine(
                                color = emitterColor,
                                start = Offset(phoneLeft + phoneWidth * 0.7f, phoneTop - 7.dp.toPx()),
                                end = Offset(phoneLeft + phoneWidth * 0.6f, phoneTop - 3.dp.toPx()),
                                strokeWidth = emitterStroke,
                                cap = StrokeCap.Round
                            )
                            // Ray 5 (far-right)
                            drawLine(
                                color = emitterColor,
                                start = Offset(phoneLeft + phoneWidth * 0.9f, phoneTop - 5.dp.toPx()),
                                end = Offset(phoneLeft + phoneWidth * 0.75f, phoneTop - 2.dp.toPx()),
                                strokeWidth = emitterStroke,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "CRIMSON DECK",
                    style = TextStyle(
                        color = darkMaroon,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp,
                        shadow = Shadow(
                            color = darkMaroon.copy(alpha = 0.7f),
                            offset = Offset(0f, 0f),
                            blurRadius = 14f
                        )
                    ),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Zero-Overhead Remote Control Bridge",
                    color = Color(0xFF9E92AC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Connection Panel (Manual Input Card)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = panelBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderGradient, RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "WORKSTATION IP ADDRESS OR HOSTNAME",
                            color = neonCrimson,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        BasicTextField(
                            value = ipInput,
                            onValueChange = { ipInput = it },
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            cursorBrush = SolidColor(neonCrimson),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0F0B14))
                                .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (ipInput.isEmpty()) {
                                        Text(
                                            text = "e.g. genesis or 100.86.174.101",
                                            color = Color(0xFF6B5B75),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        val isConnected = viewModel.isConnected
                        val buttonText = if (isConnected) "CONNECT" else "START LINK"
                        val buttonBg = if (isConnected) {
                            Brush.horizontalGradient(listOf(Color(0xFF0F9B4E), Color(0xFF004D20)))
                        } else {
                            crimsonGradient
                        }

                        Button(
                            onClick = {
                                if (isConnected) {
                                    onNavigateToStream()
                                } else {
                                    viewModel.connectToWorkstation(ipInput.trim())
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(buttonBg, RoundedCornerShape(14.dp))
                        ) {
                            if (viewModel.isConnecting) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Text(
                                    text = buttonText,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }
                        }

                        if (isConnected) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    viewModel.disconnect()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                contentPadding = PaddingValues(),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(
                                        Brush.horizontalGradient(listOf(Color(0xFF4A1525), Color(0xFF20050B))),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .border(1.dp, Color(0xFFFF0055).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            ) {
                                Text(
                                    text = "DISCONNECT LINK",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp
                                )
                            }
                        }
                    }
                }
            }

            // Symmetrical UI Action Buttons above the DISCOVERED WORKSTATIONS header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    var showUploadMenu by remember { mutableStateOf(false) }

                    // PUSH (UPLOAD) CAPSULAR BUTTON
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = if (viewModel.isConnected) {
                                        listOf(neonCrimson, darkMaroon)
                                    } else {
                                        listOf(Color(0xFF261922), Color(0xFF160E14))
                                    }
                                )
                            )
                            .border(
                                1.dp,
                                if (viewModel.isConnected) neonCrimson.copy(alpha = 0.6f) else Color(0xFF3C2030),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                if (!viewModel.isConnected) {
                                    android.widget.Toast.makeText(context, "CONNECT WORKSTATION FOR FILE SHARING", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    showUploadMenu = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "PUSH (UPLOAD)",
                            color = if (viewModel.isConnected) Color.White else Color(0xFF8B6C7C),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp
                        )

                        DropdownMenu(
                            expanded = showUploadMenu,
                            onDismissRequest = { showUploadMenu = false },
                            modifier = Modifier
                                .background(panelBg)
                                .border(1.dp, neonCrimson.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "UPLOAD FILES",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                },
                                onClick = {
                                    showUploadMenu = false
                                    uploadFilesLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(neonCrimson.copy(alpha = 0.2f)))
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "UPLOAD FOLDER",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                },
                                onClick = {
                                    showUploadMenu = false
                                    uploadFolderLauncher.launch(null)
                                }
                            )
                        }
                    }

                    // GET (DOWNLOAD) CAPSULAR BUTTON
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = if (viewModel.isConnected) {
                                        listOf(Color(0xFF00E676), Color(0xFF004D20))
                                    } else {
                                        listOf(Color(0xFF132B1B), Color(0xFF0A160F))
                                    }
                                )
                            )
                            .border(
                                1.dp,
                                if (viewModel.isConnected) Color(0xFF00E676).copy(alpha = 0.6f) else Color(0xFF1C3A24),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                if (!viewModel.isConnected) {
                                    android.widget.Toast.makeText(context, "CONNECT WORKSTATION FOR FILE SHARING", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.fileSharingMode = 1 // Get (Download)
                                    viewModel.isFileSharingActive = true
                                    viewModel.fetchRemoteDirectory(null)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "GET (DOWNLOAD)",
                            color = if (viewModel.isConnected) Color.White else Color(0xFF6B8B77),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp
                        )
                    }
                }
            }

            // Discovery and Network Selection Dashboard
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DISCOVERED WORKSTATIONS",
                        color = Color(0xFF9E92AC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )

                    // Glow button to scan LAN
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (viewModel.isScanning) Color(0xFF2B0A16) else Color(0xFF19101F)
                            )
                            .border(
                                1.dp,
                                if (viewModel.isScanning) neonCrimson else Color(0xFF4C305F),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !viewModel.isScanning) {
                                viewModel.scanLocalSubnet()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (viewModel.isScanning) "SCANNING..." else "SCAN WI-FI LAN",
                            color = if (viewModel.isScanning) neonCrimson else Color(0xFFD4C2D0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // Discovered workstation list
            if (viewModel.discoveredWorkstations.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(panelBg)
                            .border(1.dp, borderGradient, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No workstations found. Start LAN scan or run host engine to broadcast automatically.",
                            color = Color(0xFF6B5B75),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(viewModel.discoveredWorkstations) { workstation ->
                    WorkstationSelectCard(
                        workstation = workstation,
                        onClick = {
                            ipInput = workstation.ip
                            viewModel.connectToWorkstation(workstation.ip)
                        },
                        neonCrimson = neonCrimson,
                        panelBg = panelBg
                    )
                }
            }

            // DNS/MagicDNS Help Alert
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF130E17)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "TAILSCALE MAGICDNS TIP",
                            color = neonCrimson,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "If connected to Tailscale VPN on this device, you can enter your workstation's network hostname (e.g. genesis) instead of its IP address to resolve it automatically.",
                            color = Color(0xFF9E92AC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // Cyberpunk floating settings button in top-right corner - Declared at bottom of Box to float on top of LazyColumn
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 24.dp)
                .width(76.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                .background(panelBg)
                .border(
                    1.dp,
                    neonCrimson.copy(alpha = 0.4f),
                    RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
                )
                .clickable { onNavigateToSettings() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SETTINGS",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.sp,
                letterSpacing = 1.sp
            )
        }
    }

    // Connect navigator trigger
    var wasConnecting by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.isConnecting) {
        if (viewModel.isConnecting) {
            wasConnecting = true
        }
    }

    LaunchedEffect(viewModel.isConnected) {
        if (viewModel.isConnected && wasConnecting) {
            wasConnecting = false
            onNavigateToStream()
        }
    }

    if (viewModel.isFileSharingActive) {
        AlertDialog(
            onDismissRequest = {
                if (!viewModel.isTransferringFiles) {
                    viewModel.isFileSharingActive = false
                }
            },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            ),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, neonCrimson.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .background(panelBg),
            title = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (viewModel.fileSharingMode == 0) "PUSH DESTINATION" else "GET (DOWNLOAD) SYSTEM",
                        color = neonCrimson,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = viewModel.currentRemotePath,
                        color = Color(0xFF9E92AC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            text = {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (viewModel.isTransferringFiles) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = neonCrimson,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "TRANSFERRING FILES...",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (viewModel.fileTransferProgress > 0f) {
                                LinearProgressIndicator(
                                    progress = { viewModel.fileTransferProgress },
                                    color = neonCrimson,
                                    trackColor = darkMaroon,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${(viewModel.fileTransferProgress * 100).toInt()}%",
                                        color = Color(0xFF9E92AC),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = viewModel.fileTransferProgressText,
                                        color = Color(0xFF9E92AC),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                LinearProgressIndicator(
                                    color = neonCrimson,
                                    trackColor = darkMaroon,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = viewModel.fileTransferProgressText,
                                    color = Color(0xFF9E92AC),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }

                            if (viewModel.fileTransferSpeedText.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = viewModel.fileTransferSpeedText,
                                    color = neonCrimson,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // CANCEL TRANSFER BUTTON
                            Button(
                                onClick = {
                                    viewModel.cancelFileTransfer()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A1525)),
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(42.dp)
                                    .border(1.dp, neonCrimson.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "CANCEL TRANSFER",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (viewModel.remoteParentPath.isNotEmpty()) {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF1A1220))
                                                .clickable {
                                                    viewModel.fetchRemoteDirectory(viewModel.remoteParentPath)
                                                }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "📁",
                                                fontSize = 18.sp,
                                                modifier = Modifier.padding(end = 10.dp)
                                            )
                                            Text(
                                                text = "../ (Go Up)",
                                                color = neonCrimson,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                if (viewModel.remoteItems.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Empty Directory",
                                                color = Color(0xFF6B5B75),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    items(viewModel.remoteItems) { item ->
                                        val isSelected = viewModel.remoteSelectedItems.contains(item.path)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isSelected) Color(0xFF33091B) else Color(0xFF0F0B14)
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isSelected) neonCrimson.copy(alpha = 0.4f) else Color.Transparent,
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .clickable {
                                                    if (item.isDir) {
                                                        viewModel.fetchRemoteDirectory(item.path)
                                                    } else if (viewModel.fileSharingMode == 1) {
                                                        if (isSelected) {
                                                            viewModel.remoteSelectedItems.remove(item.path)
                                                        } else {
                                                            viewModel.remoteSelectedItems.add(item.path)
                                                        }
                                                    }
                                                }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (viewModel.fileSharingMode == 1) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { checked ->
                                                        if (checked == true) {
                                                            viewModel.remoteSelectedItems.add(item.path)
                                                        } else {
                                                            viewModel.remoteSelectedItems.remove(item.path)
                                                        }
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = neonCrimson,
                                                        uncheckedColor = Color(0xFF6B5B75)
                                                    ),
                                                    modifier = Modifier.padding(end = 8.dp)
                                                )
                                            }

                                            Text(
                                                text = if (item.isDir) "📁" else "📄",
                                                fontSize = 18.sp,
                                                modifier = Modifier.padding(end = 10.dp)
                                            )

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1
                                                )
                                                if (!item.isDir) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = formatFileSize(item.size),
                                                        color = Color(0xFF9E92AC),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }

                                            if (item.isDir && viewModel.fileSharingMode == 1) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isSelected) neonCrimson else Color(0xFF1D1426))
                                                        .clickable {
                                                            if (isSelected) {
                                                                viewModel.remoteSelectedItems.remove(item.path)
                                                            } else {
                                                                viewModel.remoteSelectedItems.add(item.path)
                                                            }
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = if (isSelected) "SELECTED" else "SELECT DIR",
                                                        color = if (isSelected) Color.White else Color(0xFFD4C2D0),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.ExtraBold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (!viewModel.isTransferringFiles) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.isFileSharingActive = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D1426)),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .border(1.dp, Color(0xFF4C305F), RoundedCornerShape(8.dp)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "CLOSE",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = {
                                newFolderName = ""
                                showNewFolderDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C2030)),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .border(1.dp, neonCrimson.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "NEW FOLDER",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }

                        val actionText = if (viewModel.fileSharingMode == 0) "UPLOAD HERE" else "DOWNLOAD SELECTED (${viewModel.remoteSelectedItems.size})"
                        val isEnabled = if (viewModel.fileSharingMode == 0) true else viewModel.remoteSelectedItems.isNotEmpty()

                        Button(
                            onClick = {
                                if (viewModel.fileSharingMode == 0) {
                                    viewModel.uploadSelectedAndroidItems(
                                        uris = viewModel.pendingUploadUris,
                                        isFolder = viewModel.isPendingUploadDirectory,
                                        targetHostPath = viewModel.currentRemotePath,
                                        onSuccess = {
                                            android.widget.Toast.makeText(context, "UPLOAD SUCCESSFUL", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            android.widget.Toast.makeText(context, "UPLOAD FAILED: $err", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    )
                                } else {
                                    downloadFolderLauncher.launch(null)
                                }
                            },
                            enabled = isEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isEnabled) neonCrimson else Color(0xFF261922)
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .border(
                                    1.dp,
                                    if (isEnabled) neonCrimson.copy(alpha = 0.4f) else Color(0xFF4C305F).copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp)
                                ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = actionText,
                                color = if (isEnabled) Color.White else Color(0xFF6B5B75),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        )

        if (showNewFolderDialog) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showNewFolderDialog = false },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, neonCrimson.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .background(panelBg)
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "CREATE REMOTE FOLDER",
                            color = neonCrimson,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF16101E))
                                .border(1.dp, neonCrimson.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (newFolderName.isEmpty()) {
                                Text(
                                    text = "Enter folder name...",
                                    color = Color(0xFF6B5B75),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            BasicTextField(
                                value = newFolderName,
                                onValueChange = { newFolderName = it },
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(neonCrimson),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(20.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { showNewFolderDialog = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D1426)),
                                modifier = Modifier
                                    .weight(1f)
                                    .border(1.dp, Color(0xFF4C305F), RoundedCornerShape(8.dp)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "CANCEL",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Button(
                                onClick = {
                                    val trimmed = newFolderName.trim()
                                    if (trimmed.isNotEmpty()) {
                                        viewModel.createRemoteDirectory(
                                            folderName = trimmed,
                                            onSuccess = {
                                                android.widget.Toast.makeText(context, "Folder Created Successfully", android.widget.Toast.LENGTH_SHORT).show()
                                                showNewFolderDialog = false
                                            },
                                            onError = { err ->
                                                android.widget.Toast.makeText(context, "Failed to Create Folder: $err", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        )
                                    }
                                },
                                enabled = newFolderName.trim().isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (newFolderName.trim().isNotEmpty()) neonCrimson else Color(0xFF261922)
                                ),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "CREATE",
                                    color = if (newFolderName.trim().isNotEmpty()) Color.White else Color(0xFF6B5B75),
                                    fontSize = 11.sp,
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

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Composable
fun WorkstationSelectCard(
    workstation: DiscoveredWorkstation,
    onClick: () -> Unit,
    neonCrimson: Color,
    panelBg: Color
) {
    val badgeColor = when (workstation.type) {
        "Wi-Fi LAN" -> Color(0xFF00E676)
        "Tailscale" -> neonCrimson
        else -> Color(0xFF9E92AC) // Recent
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = panelBg),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(badgeColor.copy(alpha = 0.3f), Color.Transparent)),
                RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workstation.name.uppercase(),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = workstation.ip,
                    color = Color(0xFFD4C2D0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            // Dynamic Badge indicators
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeColor.copy(alpha = 0.15f))
                    .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = workstation.type.uppercase(),
                    color = badgeColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
