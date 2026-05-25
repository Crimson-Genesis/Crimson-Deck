package com.crimson.deck.ui.screens

import android.graphics.Bitmap
import android.content.Context
import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import android.content.ClipboardManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import com.crimson.deck.ui.viewmodel.AgentViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StreamScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    BackHandler {
        onBack()
    }

    DisposableEffect(Unit) {
        viewModel.connectStream()
        onDispose {
            viewModel.disconnectStream()
        }
    }

    LaunchedEffect(viewModel.isConnected, viewModel.explicitlyDisconnected) {
        if (!viewModel.isConnected && !viewModel.isConnecting && !viewModel.explicitlyDisconnected) {
            viewModel.connectToWorkstation(viewModel.serverHost)
        }
    }

    LaunchedEffect(viewModel.virtualMousePos) {
        if (viewModel.virtualMousePos != null) {
            delay(viewModel.cursorHideDelayMs)
            viewModel.virtualMousePos = null
        }
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var accumulatedDragX by remember { mutableStateOf(0f) }
    var accumulatedScrollY by remember { mutableStateOf(0f) }
    var isViewportLocked by remember { mutableStateOf(false) }
    var swipeTriggered by remember { mutableStateOf(false) }
    var fingerCount by remember { mutableStateOf(0) }
    var showClipHistoryDialog by remember { mutableStateOf(false) }

    // Dynamic Cyberpunk Theme Colors from ViewModel
    val obsidianDark = viewModel.themeBackground
    val neonCrimson = viewModel.themePrimary
    val darkMaroon = viewModel.themeSecondary
    val panelBg = viewModel.themePanel


    // Breathing pulse animation for active link
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(obsidianDark)
    ) {
        // 1. High-Performance Hardware-Accelerated Display Canvas (Fills screen to capture gestures in empty gap)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { canvasSize = it.size }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            fingerCount = event.changes.count { it.pressed }
                            val allUp = event.changes.all { !it.pressed }
                            if (allUp) {
                                swipeTriggered = false
                                accumulatedDragX = 0f
                                accumulatedScrollY = 0f
                                fingerCount = 0
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val isConnected = viewModel.isConnected
                        val streamWidth = viewModel.streamWidth
                        val streamHeight = viewModel.streamHeight
                        var isOnVideo = false
                        var xStart = 0f
                        var yStart = 0f
                        var wDraw = 0f
                        var hDraw = 0f
                        
                        if (isConnected && canvasSize.width > 0 && canvasSize.height > 0) {
                            val safeHeight = canvasSize.height.toFloat() * 0.70f
                            val R_ws = streamWidth.toFloat() / streamHeight.toFloat()
                            val R_canvas = canvasSize.width.toFloat() / safeHeight
                            if (R_ws > R_canvas) {
                                wDraw = canvasSize.width.toFloat()
                                hDraw = canvasSize.width.toFloat() / R_ws
                            } else {
                                hDraw = safeHeight
                                wDraw = safeHeight * R_ws
                            }
                            xStart = (canvasSize.width - wDraw) / 2f
                            yStart = safeHeight * 0.25f
                            
                            val left = xStart * scale + offset.x
                            val right = (xStart + wDraw) * scale + offset.x
                            val top = yStart * scale + offset.y
                            val bottom = (yStart + hDraw) * scale + offset.y
                            
                            isOnVideo = centroid.x >= left && 
                                        centroid.x <= right && 
                                        centroid.y >= top && 
                                        centroid.y <= bottom
                        }
                        
                        if (!isOnVideo) {
                            // Touch/Gesture is in the blank empty space: Trigger WS swipes only (works zoomed in or out!)
                            if (!swipeTriggered && fingerCount == 1 && zoom == 1f) {
                                accumulatedDragX += pan.x
                                val swipeThreshold = 120f
                                if (accumulatedDragX > swipeThreshold) {
                                    swipeTriggered = true
                                    val workspaces = viewModel.workspacesList
                                    if (workspaces.isNotEmpty()) {
                                        val minId = workspaces.first().id
                                        val maxId = workspaces.last().id
                                        val prevWs = if (viewModel.activeWorkspace == minId) maxId else viewModel.activeWorkspace - 1
                                        viewModel.sendWorkspaceById(prevWs)
                                    }
                                } else if (accumulatedDragX < -swipeThreshold) {
                                    swipeTriggered = true
                                    val workspaces = viewModel.workspacesList
                                    if (workspaces.isNotEmpty()) {
                                        val minId = workspaces.first().id
                                        val maxId = workspaces.last().id
                                        val nextWs = if (viewModel.activeWorkspace == maxId) minId else viewModel.activeWorkspace + 1
                                        viewModel.sendWorkspaceById(nextWs)
                                    }
                                }
                            }
                        } else {
                            // Touch/Gesture is on top of the video stream
                            if (isViewportLocked) {
                                // Viewport is locked: Vertical drags trigger mouse scrolling, no zoom or pan
                                if (fingerCount == 1 && zoom == 1f) {
                                    accumulatedScrollY += pan.y
                                    val scrollThreshold = 35f
                                    if (accumulatedScrollY > scrollThreshold) {
                                        viewModel.sendMouseScroll(1) // Scroll Up (downward drag)
                                        accumulatedScrollY = 0f
                                    } else if (accumulatedScrollY < -scrollThreshold) {
                                        viewModel.sendMouseScroll(-1) // Scroll Down (upward drag)
                                        accumulatedScrollY = 0f
                                    }
                                }
                            } else {
                                // Viewport is unlocked: Trigger zoom/panning if zoomed or zooming
                                if (scale > 1.05f || zoom != 1f) {
                                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                    val scaleDiff = nextScale / scale
                                    
                                    val rawX = centroid.x - scaleDiff * (centroid.x - offset.x) + pan.x
                                    val rawY = centroid.y - scaleDiff * (centroid.y - offset.y) + pan.y
                                    
                                    if (isConnected && canvasSize.width > 0 && canvasSize.height > 0) {
                                        val safeHeight = canvasSize.height.toFloat() * 0.70f
                                        offset = clampOffset(
                                            offset = Offset(rawX, rawY),
                                            scale = nextScale,
                                            canvasWidth = canvasSize.width.toFloat(),
                                            canvasHeight = safeHeight,
                                            wDraw = wDraw,
                                            hDraw = hDraw,
                                            xStart = xStart,
                                            yStart = yStart
                                        )
                                    } else {
                                        offset = Offset(rawX, rawY)
                                    }
                                    scale = nextScale
                                } else {
                                    // Unzoomed viewport: Vertical drags trigger mouse scrolling
                                    if (fingerCount == 1 && zoom == 1f) {
                                        accumulatedScrollY += pan.y
                                        val scrollThreshold = 35f
                                        if (accumulatedScrollY > scrollThreshold) {
                                            viewModel.sendMouseScroll(1) // Scroll Up (downward drag)
                                            accumulatedScrollY = 0f
                                        } else if (accumulatedScrollY < -scrollThreshold) {
                                            viewModel.sendMouseScroll(-1) // Scroll Down (upward drag)
                                            accumulatedScrollY = 0f
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .pointerInput(canvasSize, scale, offset) {
                    detectTapGestures(
                        onDoubleTap = { touchOffset ->
                            val isConnected = viewModel.isConnected
                            val streamWidth = viewModel.streamWidth
                            val streamHeight = viewModel.streamHeight
                            if (isConnected && canvasSize.width > 0 && canvasSize.height > 0) {
                                val coords = getMappedCoordinates(touchOffset, canvasSize, scale, offset, streamWidth, streamHeight)
                                if (coords == null) {
                                    // Tapped in the empty space!
                                    if (scale > 1.05f || offset != Offset.Zero) {
                                        // Reset viewport if currently zoomed in
                                        scale = 1f
                                        offset = Offset.Zero
                                        isViewportLocked = false
                                        viewModel.virtualMousePos = null
                                    } else {
                                        // Zoom in to the center if currently unzoomed
                                        val safeHeight = canvasSize.height.toFloat() * 0.70f
                                        val R_ws = streamWidth.toFloat() / streamHeight.toFloat()
                                        val R_canvas = canvasSize.width.toFloat() / safeHeight
                                        
                                        val wDraw: Float
                                        val hDraw: Float
                                        if (R_ws > R_canvas) {
                                            wDraw = canvasSize.width.toFloat()
                                            hDraw = canvasSize.width.toFloat() / R_ws
                                        } else {
                                            hDraw = safeHeight
                                            wDraw = safeHeight * R_ws
                                        }
                                        
                                        val xStart = (canvasSize.width - wDraw) / 2f
                                        val yStart = safeHeight * 0.25f

                                        val targetScale = viewModel.doubleTapZoomScale
                                        val targetOffset = Offset(
                                            canvasSize.width / 2f - (xStart + wDraw / 2f) * targetScale,
                                            safeHeight / 2f - (yStart + hDraw / 2f) * targetScale
                                        )

                                        val clamped = clampOffset(
                                            offset = targetOffset,
                                            scale = targetScale,
                                            canvasWidth = canvasSize.width.toFloat(),
                                            canvasHeight = safeHeight,
                                            wDraw = wDraw,
                                            hDraw = hDraw,
                                            xStart = xStart,
                                            yStart = yStart
                                        )

                                        scale = targetScale
                                        offset = clamped
                                    }
                                }
                            }
                        },
                        onTap = { touchOffset ->
                            if (!viewModel.isConnected) return@detectTapGestures
                            val streamWidth = viewModel.streamWidth
                            val streamHeight = viewModel.streamHeight
                            val coords = getMappedCoordinates(touchOffset, canvasSize, scale, offset, streamWidth, streamHeight)
                            if (coords != null) {
                                val (clampedX, clampedY) = coords
                                injectAbsoluteTap(viewModel, clampedX, clampedY, streamWidth, streamHeight)
                            }
                        }
                    )
                }
                .pointerInput(canvasSize, scale, offset) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { touchOffset ->
                            if (!viewModel.isConnected) return@detectDragGesturesAfterLongPress
                            val streamWidth = viewModel.streamWidth
                            val streamHeight = viewModel.streamHeight
                            val coords = getMappedCoordinates(touchOffset, canvasSize, scale, offset, streamWidth, streamHeight)
                            if (coords != null) {
                                val (clampedX, clampedY) = coords
                                viewModel.virtualMousePos = Pair(clampedX, clampedY)
                                
                                if (viewModel.ctrlActive) viewModel.sendKey(29, true)
                                if (viewModel.altActive) viewModel.sendKey(56, true)
                                if (viewModel.shiftActive) viewModel.sendKey(42, true)
                                if (viewModel.superActive) viewModel.sendKey(125, true)
                                
                                viewModel.sendMouseAbsolute(clampedX, clampedY, streamWidth, streamHeight)
                                viewModel.sendMouseClick(272, true)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (!viewModel.isConnected) return@detectDragGesturesAfterLongPress
                            val streamWidth = viewModel.streamWidth
                            val streamHeight = viewModel.streamHeight
                            val coords = getMappedCoordinates(change.position, canvasSize, scale, offset, streamWidth, streamHeight)
                            if (coords != null) {
                                val (clampedX, clampedY) = coords
                                viewModel.virtualMousePos = Pair(clampedX, clampedY)
                                viewModel.sendMouseAbsolute(clampedX, clampedY, streamWidth, streamHeight)
                            }
                        },
                        onDragEnd = {
                            viewModel.sendMouseClick(272, false)
                            
                            if (viewModel.ctrlActive) { viewModel.sendKey(29, false); viewModel.ctrlActive = false }
                            if (viewModel.altActive) { viewModel.sendKey(56, false); viewModel.altActive = false }
                            if (viewModel.shiftActive) { viewModel.sendKey(42, false); viewModel.shiftActive = false }
                            if (viewModel.superActive) { viewModel.sendKey(125, false); viewModel.superActive = false }
                        },
                        onDragCancel = {
                            viewModel.sendMouseClick(272, false)
                            
                            if (viewModel.ctrlActive) { viewModel.sendKey(29, false); viewModel.ctrlActive = false }
                            if (viewModel.altActive) { viewModel.sendKey(56, false); viewModel.altActive = false }
                            if (viewModel.shiftActive) { viewModel.sendKey(42, false); viewModel.shiftActive = false }
                            if (viewModel.superActive) { viewModel.sendKey(125, false); viewModel.superActive = false }
                        }
                    )
                }
        ) {
            val safeHeight = canvasSize.height.toFloat() * 0.70f
            
            if (viewModel.isConnected && canvasSize.width > 0 && canvasSize.height > 0) {
                val R_ws = viewModel.streamWidth.toFloat() / viewModel.streamHeight.toFloat()
                val R_canvas = canvasSize.width.toFloat() / safeHeight
                
                val wDraw: Float
                val hDraw: Float
                if (R_ws > R_canvas) {
                    wDraw = canvasSize.width.toFloat()
                    hDraw = canvasSize.width.toFloat() / R_ws
                } else {
                    hDraw = safeHeight
                    wDraw = safeHeight * R_ws
                }
                
                val xStart = (canvasSize.width - wDraw) / 2f
                val yStart = safeHeight * 0.25f
                
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                    val surface = Surface(surfaceTexture)
                                    viewModel.h264Decoder.setSurface(surface)
                                    viewModel.requestKeyframe()
                                }

                                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

                                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                    viewModel.h264Decoder.setSurface(null)
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier
                        .size(
                            width = with(LocalDensity.current) { wDraw.toDp() },
                            height = with(LocalDensity.current) { hDraw.toDp() }
                        )
                        .graphicsLayer {
                            translationX = offset.x + xStart * scale
                            translationY = offset.y + yStart * scale
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                clipRect(left = 0f, top = 0f, right = size.width, bottom = safeHeight) {
                    if (viewModel.isConnected) {
                        val R_ws = viewModel.streamWidth.toFloat() / viewModel.streamHeight.toFloat()
                        val R_canvas = size.width / safeHeight
                        
                        val wDraw: Float
                        val hDraw: Float
                        if (R_ws > R_canvas) {
                            wDraw = size.width
                            hDraw = size.width / R_ws
                        } else {
                            hDraw = safeHeight
                            wDraw = safeHeight * R_ws
                        }
                        
                        val xStart = (size.width - wDraw) / 2f
                        val yStart = safeHeight * 0.25f
                        
                        withTransform({
                            translate(offset.x, offset.y)
                            scale(scale, scale, pivot = Offset.Zero)
                        }) {
                            val mousePos = viewModel.virtualMousePos
                            if (mousePos != null) {
                                val canvasX = xStart + (mousePos.first.toFloat() * wDraw) / viewModel.streamWidth.toFloat()
                                val canvasY = yStart + (mousePos.second.toFloat() * hDraw) / viewModel.streamHeight.toFloat()
                                
                                drawCircle(
                                    color = neonCrimson,
                                    radius = 6.dp.toPx(),
                                    center = Offset(canvasX, canvasY),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 2.dp.toPx(),
                                    center = Offset(canvasX, canvasY)
                                )
                            }
                        }
                    } else {
                        // Deep dark neon mesh grid as a waiting stub
                        drawRect(color = Color(0xFF0F0C12))
                    }
                }
            }
        }

        // 2. Floating Header Action Deck (Cool-looking Asymmetric Chamfered Capsules)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Elegant back button with futuristic tactical chamfered corners
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

                // Connection state indicator badge with glowing tactical border and optional live stats
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(topEnd = 12.dp, bottomStart = 12.dp))
                            .background(panelBg)
                            .border(
                                1.dp,
                                Brush.horizontalGradient(listOf(Color.Transparent, neonCrimson.copy(alpha = 0.6f))),
                                RoundedCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
                            )
                            .padding(horizontal = 14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (viewModel.isConnected) neonCrimson.copy(alpha = pulseAlpha)
                                    else Color(0xFFFFCC00).copy(alpha = pulseAlpha)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (viewModel.isConnected) "LINK ACTIVE" else "CONNECTING",
                            color = if (viewModel.isConnected) neonCrimson else Color(0xFFFFCC00),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp
                        )
                    }

                    if (viewModel.showStreamStats && viewModel.isConnected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .width(165.dp)
                                .height(38.dp)
                                .clip(RoundedCornerShape(topEnd = 12.dp, bottomStart = 12.dp))
                                .background(panelBg)
                                .border(
                                    1.dp,
                                    neonCrimson.copy(alpha = 0.35f),
                                    RoundedCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
                                )
                                .padding(horizontal = 10.dp)
                        ) {
                            val bitrateText = if (viewModel.streamBitrateKbps >= 1000.0) {
                                String.format("%.1f Mbps", viewModel.streamBitrateKbps / 1000.0)
                            } else {
                                String.format("%.0f Kbps", viewModel.streamBitrateKbps)
                            }
                            Text(
                                text = "${viewModel.streamFps} FPS | $bitrateText | ${viewModel.streamResolution}",
                                color = Color(0xFFD4C2D0),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // Dropdown menu for custom macros
            var macroExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                        .background(panelBg)
                        .border(
                            1.dp,
                            neonCrimson.copy(alpha = 0.5f),
                            RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
                        )
                        .clickable { macroExpanded = !macroExpanded }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MACROS ▼",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp
                    )

                    DropdownMenu(
                        expanded = macroExpanded,
                        onDismissRequest = { macroExpanded = false },
                        modifier = Modifier
                            .background(panelBg)
                            .border(1.dp, neonCrimson.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .heightIn(max = 280.dp)
                    ) {
                        if (viewModel.customMacros.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "NO CUSTOM MACROS",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                },
                                onClick = {}
                            )
                        } else {
                            viewModel.customMacros.forEach { macro ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = macro.name.uppercase(),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.5.sp
                                        )
                                    },
                                    onClick = {
                                        macroExpanded = false
                                        viewModel.executeMacro(macro)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Floating Overlay Input Controller Deck
        Card(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = panelBg),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        colors = listOf(neonCrimson.copy(alpha = 0.35f), Color.Transparent)
                    ),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Beautiful Cyberpunk Workspace selection strip (Moved to bottom above inputs)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F0B14))
                        .border(
                            1.dp,
                            Brush.verticalGradient(listOf(neonCrimson.copy(alpha = 0.25f), Color.Transparent)),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WS",
                        color = neonCrimson,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(end = 2.dp, start = 2.dp)
                    )
                    
                    // Dynamic custom workspaces from config
                    for (wsItem in viewModel.workspacesList) {
                        val isActive = wsItem.id == viewModel.activeWorkspace
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
                                .background(if (isActive) neonCrimson else Color(0xFF07040A))
                                .border(
                                    1.dp,
                                    if (isActive) neonCrimson else neonCrimson.copy(alpha = 0.2f),
                                    RoundedCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
                                )
                                .clickable {
                                    viewModel.sendWorkspaceById(wsItem.id)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = wsItem.display,
                                color = if (isActive) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Compact Termux-like Developer Toolbar (Two Rows)
                // Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SystemKeyButton("ESC", Modifier.weight(1f)) { viewModel.tapKey(1) }
                    ModifierKeyToggle(
                        label = "CTRL",
                        isActive = viewModel.ctrlActive,
                        onClick = {
                            viewModel.ctrlActive = !viewModel.ctrlActive
                            viewModel.sendKey(29, viewModel.ctrlActive)
                        },
                        activeBg = Brush.horizontalGradient(listOf(neonCrimson, darkMaroon))
                    )
                    ModifierKeyToggle(
                        label = "ALT",
                        isActive = viewModel.altActive,
                        onClick = {
                            viewModel.altActive = !viewModel.altActive
                            viewModel.sendKey(56, viewModel.altActive)
                        },
                        activeBg = Brush.horizontalGradient(listOf(neonCrimson, darkMaroon))
                    )
                    SystemKeyButton(
                        label = "TAB",
                        modifier = Modifier.weight(1f),
                        isRepeatAllowed = viewModel.allowedRepeatKeys.contains("TAB"),
                        delayMs = viewModel.repeatKeyDelay.toLong()
                    ) { viewModel.tapKey(15) }
                    SystemKeyButton(
                        label = "BKSP",
                        modifier = Modifier.weight(1f),
                        isRepeatAllowed = viewModel.allowedRepeatKeys.contains("BKSP"),
                        delayMs = viewModel.repeatKeyDelay.toLong()
                    ) { viewModel.tapKey(14) }
                    SystemKeyButton(
                        label = "▲",
                        modifier = Modifier.weight(1f),
                        isRepeatAllowed = viewModel.allowedRepeatKeys.contains("▲"),
                        delayMs = viewModel.repeatKeyDelay.toLong()
                    ) { viewModel.tapKey(103) }
                    SystemKeyButton(
                        label = "ENT",
                        modifier = Modifier.weight(1f),
                        isRepeatAllowed = viewModel.allowedRepeatKeys.contains("ENT"),
                        delayMs = viewModel.repeatKeyDelay.toLong()
                    ) { viewModel.tapKey(28) }
                }

                // Row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SystemKeyButton("FULL", Modifier.weight(1f)) {
                        viewModel.toggleFullscreen()
                    }
                    ModifierKeyToggle(
                        label = "SUPR",
                        isActive = viewModel.superActive,
                        onClick = {
                            viewModel.superActive = !viewModel.superActive
                            viewModel.sendKey(125, viewModel.superActive)
                        },
                        activeBg = Brush.horizontalGradient(listOf(neonCrimson, darkMaroon))
                    )
                    ModifierKeyToggle(
                        label = "SHFT",
                        isActive = viewModel.shiftActive,
                        onClick = {
                            viewModel.shiftActive = !viewModel.shiftActive
                            viewModel.sendKey(42, viewModel.shiftActive)
                        },
                        activeBg = Brush.horizontalGradient(listOf(neonCrimson, darkMaroon))
                    )
                    SystemKeyButton(
                        label = "SPC",
                        modifier = Modifier.weight(1f),
                        isRepeatAllowed = viewModel.allowedRepeatKeys.contains("SPC"),
                        delayMs = viewModel.repeatKeyDelay.toLong()
                    ) { viewModel.tapKey(57) }
                    SystemKeyButton(
                        label = "◀",
                        modifier = Modifier.weight(1f),
                        isRepeatAllowed = viewModel.allowedRepeatKeys.contains("◀"),
                        delayMs = viewModel.repeatKeyDelay.toLong()
                    ) { viewModel.tapKey(105) }
                    SystemKeyButton(
                        label = "▼",
                        modifier = Modifier.weight(1f),
                        isRepeatAllowed = viewModel.allowedRepeatKeys.contains("▼"),
                        delayMs = viewModel.repeatKeyDelay.toLong()
                    ) { viewModel.tapKey(108) }
                    SystemKeyButton(
                        label = "▶",
                        modifier = Modifier.weight(1f),
                        isRepeatAllowed = viewModel.allowedRepeatKeys.contains("▶"),
                        delayMs = viewModel.repeatKeyDelay.toLong()
                    ) { viewModel.tapKey(106) }
                }

                // Keyboard character typing input and Clipboard sync deck (Moved to bottom most)
                
                if (viewModel.currentKeyboardMode == 0) {
                    // 1. Buffered Input Mode (Standard Text Box)
                    var keyboardInputState by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BasicTextField(
                            value = keyboardInputState,
                            onValueChange = { newValue ->
                                if (newValue.text.isEmpty()) {
                                    // Space deleted -> Backspace!
                                    viewModel.tapKey(14)
                                    keyboardInputState = TextFieldValue(" ", selection = TextRange(1))
                                } else {
                                    keyboardInputState = newValue
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onAny = {
                                    val textToSend = keyboardInputState.text.substring(1)
                                    if (textToSend.isNotEmpty()) {
                                        viewModel.sendText(textToSend)
                                        viewModel.addToSentItemsHistory(textToSend)
                                        keyboardInputState = TextFieldValue(" ", selection = TextRange(1))
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(Color(0xFF0F0B14), RoundedCornerShape(8.dp))
                                .border(1.dp, neonCrimson.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp),
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (keyboardInputState.text == " ") {
                                        Text("Type keyboard...", color = Color(0xFF6B5B75), fontSize = 10.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        
                        // "TYPE" Button
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.horizontalGradient(listOf(neonCrimson, darkMaroon))
                                )
                                .clickable {
                                    val textToSend = keyboardInputState.text.substring(1)
                                    if (textToSend.isNotEmpty()) {
                                        viewModel.sendText(textToSend)
                                        viewModel.addToSentItemsHistory(textToSend)
                                        keyboardInputState = TextFieldValue(" ", selection = TextRange(1))
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "TYPE",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        }

                        // "CLIP" Button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF100C14))
                                .border(1.dp, Color(0xFF33202E), RoundedCornerShape(8.dp))
                                .clickable {
                                    showClipHistoryDialog = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "CLIP",
                                color = Color(0xFFC299A6),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                } else {
                    // 2. Direct Keyboard Mode (Raw Real-Time Hardware Keypress Interceptor)
                    var directInputState by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
                    val scope = rememberCoroutineScope()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BasicTextField(
                            value = directInputState,
                            onValueChange = { newValue ->
                                if (newValue.text.isEmpty()) {
                                    // Space deleted -> Backspace!
                                    viewModel.tapKey(14)
                                    directInputState = TextFieldValue(" ", selection = TextRange(1))
                                } else if (newValue.text.length > 1) {
                                    val typedChars = newValue.text.substring(1)
                                    if (typedChars == "\n") {
                                        // Enter pressed!
                                        viewModel.tapKey(28)
                                    } else {
                                        // Process characters
                                        for (c in typedChars) {
                                            val lowercaseChar = c.lowercaseChar().toString()
                                            val code = viewModel.getKeyCodeFromFriendlyName(lowercaseChar)
                                            if (code != null) {
                                                val requiresShift = c.isUpperCase() || c in "!@#$%^&*()_+{}|:\"<>?~"
                                                scope.launch {
                                                    if (requiresShift) {
                                                        viewModel.sendKey(42, true) // Shift down
                                                        delay(20L)
                                                    }
                                                    viewModel.sendKey(code, true) // Key down
                                                    delay(50L)
                                                    viewModel.sendKey(code, false) // Key up
                                                    if (requiresShift) {
                                                        delay(20L)
                                                        viewModel.sendKey(42, false) // Shift up
                                                    }
                                                }
                                            } else {
                                                // Fallback: send as raw text string if not in keycode mapping
                                                viewModel.sendText(c.toString())
                                            }
                                        }
                                    }
                                    directInputState = TextFieldValue(" ", selection = TextRange(1))
                                } else {
                                    directInputState = newValue.copy(selection = TextRange(newValue.text.length))
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = neonCrimson, 
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            keyboardActions = KeyboardActions(
                                onAny = {
                                    // Enter pressed!
                                    viewModel.tapKey(28)
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(Color(0xFF0F0B14), RoundedCornerShape(8.dp))
                                .border(1.5.dp, neonCrimson.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp),
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (directInputState.text == " ") {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(neonCrimson.copy(alpha = pulseAlpha))
                                            )
                                            Text(
                                                text = "DIRECT RAW KEYBOARD ACTIVE - FOCUS TO TYPE...", 
                                                color = neonCrimson.copy(alpha = 0.8f), 
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                    }
                                    // Hidden text editor block that intercepts keystrokes
                                    innerTextField()
                                }
                            }
                        )

                        // "CLIP" Button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF100C14))
                                .border(1.dp, Color(0xFF33202E), RoundedCornerShape(8.dp))
                                .clickable {
                                    showClipHistoryDialog = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "CLIP",
                                color = Color(0xFFC299A6),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }

        // 4. Floating Lock & Reset View Buttons Column (Rendered only when zoomed in, styled like WS capsules)
        if (scale > 1.05f || offset != Offset.Zero) {
            Column(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .align(Alignment.CenterEnd),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // LOCK Button
                Box(
                    modifier = Modifier
                        .width(54.dp)
                        .height(26.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
                        .background(if (isViewportLocked) neonCrimson else Color(0xFF07040A))
                        .border(
                            1.dp,
                            if (isViewportLocked) neonCrimson else neonCrimson.copy(alpha = 0.2f),
                            RoundedCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
                        )
                        .clickable { isViewportLocked = !isViewportLocked },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isViewportLocked) "LOCKED" else "LOCK",
                        color = if (isViewportLocked) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }

                // RESET Button
                Box(
                    modifier = Modifier
                        .width(54.dp)
                        .height(26.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
                        .background(Color(0xFF07040A))
                        .border(
                            1.dp,
                            neonCrimson.copy(alpha = 0.2f),
                            RoundedCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
                        )
                        .clickable {
                            scale = 1f
                            offset = Offset.Zero
                            isViewportLocked = false
                            viewModel.virtualMousePos = null
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "RESET",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }

    if (showClipHistoryDialog) {
        Dialog(onDismissRequest = { showClipHistoryDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(panelBg)
                    .border(1.5.dp, neonCrimson.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "CLIPBOARD & TEXT HISTORY",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp
                    )

                    HorizontalDivider(color = Color(0xFF281E2C), thickness = 1.dp)

                    // Button to send active phone clipboard
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.horizontalGradient(listOf(neonCrimson, darkMaroon)))
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotEmpty()) {
                                        viewModel.sendClipboard(text)
                                        if (viewModel.currentKeyboardMode == 0) {
                                            viewModel.addToSentItemsHistory(text)
                                        }
                                    }
                                }
                                showClipHistoryDialog = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "SEND ACTIVE PHONE CLIPBOARD",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "RECENTLY SENT ITEMS",
                        color = neonCrimson.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    // Scrollable History list
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (viewModel.sentItemsHistory.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No items sent yet",
                                    color = Color(0xFF6B5B75),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            viewModel.sentItemsHistory.forEach { historyItem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F0B14))
                                        .border(1.dp, Color(0xFF281E2C), RoundedCornerShape(8.dp)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Clickable text snippet (sends to host)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                viewModel.sendClipboard(historyItem)
                                                showClipHistoryDialog = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = historyItem,
                                            color = Color(0xFFD4C2D0),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Normal,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Symmetrical vertical border
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(20.dp)
                                            .background(Color(0xFF281E2C))
                                    )

                                    // Individual Delete button ("✕")
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clickable {
                                                viewModel.removeSingleSentItem(historyItem)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "✕",
                                            color = neonCrimson.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF281E2C), thickness = 0.5.dp)

                    // Symmetrical Action Buttons Row (Clear all history vs Close Console)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // "CLEAR ALL" Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E101A))
                                .border(1.dp, neonCrimson.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.clearSentItemsHistory()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "CLEAR ALL",
                                color = neonCrimson,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        }

                        // "CLOSE CONSOLE" Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF16121D))
                                .border(1.dp, Color(0xFF33202E), RoundedCornerShape(8.dp))
                                .clickable {
                                    showClipHistoryDialog = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "CLOSE CONSOLE",
                                color = Color(0xFF9E92AC),
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
}

// ─────────────────────────────────────────────────────────────────────────────
// UI Components & Logic Helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RowScope.ModifierKeyToggle(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    activeBg: Brush
) {
    val innerModifier = if (isActive) {
        Modifier.background(activeBg)
    } else {
        Modifier.background(Color(0xFF100C14))
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .height(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (isActive) Color(0xFFFF0055) else Color(0xFF33202E),
                RoundedCornerShape(12.dp)
            )
            .then(innerModifier)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color.White else Color(0xFFC299A6),
            fontWeight = FontWeight.ExtraBold,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun SystemKeyButton(
    label: String,
    modifier: Modifier = Modifier,
    isRepeatAllowed: Boolean = false,
    delayMs: Long = 20L,
    onClick: () -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()

    val buttonModifier = if (isRepeatAllowed) {
        modifier.pointerInput(delayMs) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    currentOnClick()
                    val job = scope.launch {
                        delay(400L) // Standard repeat initial delay
                        while (true) {
                            currentOnClick()
                            delay(delayMs)
                        }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                }
            }
        }
    } else {
        modifier.clickable { onClick() }
    }

    Box(
        modifier = buttonModifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF281E2C), RoundedCornerShape(10.dp))
            .background(Color(0xFF120E15)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(0xFFD4C2D0),
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp
        )
    }
}

// Helper gesture extension to capture Compose Touch absolute positions
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGestures(
    onTap: (Offset) -> Unit
) {
    detectTapGestures(onTap = onTap)
}

private fun getMappedCoordinates(
    touchOffset: Offset,
    canvasSize: IntSize,
    scale: Float,
    offset: Offset,
    bmpWidth: Int,
    bmpHeight: Int
): Pair<Int, Int>? {
    if (canvasSize.width == 0 || canvasSize.height == 0) return null
    val safeHeight = canvasSize.height.toFloat() * 0.70f
    val R_ws = bmpWidth.toFloat() / bmpHeight.toFloat()
    val R_canvas = canvasSize.width.toFloat() / safeHeight
    
    val wDraw: Float
    val hDraw: Float
    if (R_ws > R_canvas) {
        wDraw = canvasSize.width.toFloat()
        hDraw = canvasSize.width.toFloat() / R_ws
    } else {
        hDraw = safeHeight
        wDraw = safeHeight * R_ws
    }
    
    val xStart = (canvasSize.width - wDraw) / 2f
    val yStart = safeHeight * 0.25f

    val physicalX = (touchOffset.x - offset.x) / scale - xStart
    val physicalY = (touchOffset.y - offset.y) / scale - yStart

    // If the touch is in the empty space surrounding the video bounds, return null
    if (physicalX < 0f || physicalX > wDraw || physicalY < 0f || physicalY > hDraw) {
        return null
    }

    val mappedX = (physicalX * bmpWidth) / wDraw
    val mappedY = (physicalY * bmpHeight) / hDraw

    val clampedX = mappedX.toInt().coerceIn(0, bmpWidth - 1)
    val clampedY = mappedY.toInt().coerceIn(0, bmpHeight - 1)

    return Pair(clampedX, clampedY)
}

// Injects an absolute coordinate pointer mapping + click event down UDS
private fun injectAbsoluteTap(viewModel: AgentViewModel, x: Int, y: Int, maxX: Int, maxY: Int) {
    if (viewModel.ctrlActive) viewModel.sendKey(29, true)
    if (viewModel.altActive) viewModel.sendKey(56, true)
    if (viewModel.shiftActive) viewModel.sendKey(42, true)
    if (viewModel.superActive) viewModel.sendKey(125, true)

    viewModel.virtualMousePos = Pair(x, y)
    viewModel.sendMouseAbsolute(x, y, maxX, maxY)

    viewModel.sendMouseClick(272, true) // 272 is BTN_LEFT (0x110)
    viewModel.sendMouseClick(272, false)

    if (viewModel.ctrlActive) { viewModel.sendKey(29, false); viewModel.ctrlActive = false }
    if (viewModel.altActive) { viewModel.sendKey(56, false); viewModel.altActive = false }
    if (viewModel.shiftActive) { viewModel.sendKey(42, false); viewModel.shiftActive = false }
    if (viewModel.superActive) { viewModel.sendKey(125, false); viewModel.superActive = false }
}

// Clamps panned offsets to keep viewport strictly bounded (no off-screen view, locked inside top 60% viewport)
private fun clampOffset(
    offset: Offset,
    scale: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    wDraw: Float,
    hDraw: Float,
    xStart: Float,
    yStart: Float
): Offset {
    if (scale <= 1.05f) return Offset.Zero

    val imgWidth = wDraw * scale
    val clampedX = if (imgWidth > canvasWidth) {
        val minX = canvasWidth - (xStart + wDraw) * scale
        val maxX = -xStart * scale
        offset.x.coerceIn(minX, maxX)
    } else {
        (canvasWidth - imgWidth) / 2f - xStart * scale
    }

    val imgHeight = hDraw * scale
    val clampedY = if (imgHeight > canvasHeight) {
        val minY = canvasHeight - (yStart + hDraw) * scale
        val maxY = -yStart * scale
        offset.y.coerceIn(minY, maxY)
    } else {
        (canvasHeight - imgHeight) / 2f - yStart * scale
    }

    return Offset(clampedX, clampedY)
}
