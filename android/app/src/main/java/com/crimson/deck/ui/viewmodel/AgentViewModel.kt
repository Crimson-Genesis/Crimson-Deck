package com.crimson.deck.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.Color
import com.crimson.deck.data.websocket.WebSocketManager
import com.google.gson.Gson
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.mutableStateListOf
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

data class CustomMacro(
    val name: String,
    val formula: String,
    val delayMs: Int = 120
)

data class WorkspaceItemConfig(
    val id: Int,
    val display: String,
    val value: String
)

data class WorkspaceOptionsConfig(
    val default_active_id: Int,
    val swipe_navigation_wrap: Boolean
)

data class AppConfig(
    val workspaces: List<WorkspaceItemConfig>,
    val workspace_options: WorkspaceOptionsConfig,
    val default_network_port: String,
    val default_discovery_hostname: String,
    val default_discovery_domain: String,
    val default_cursor_hide_delay_ms: Long,
    val default_repeat_key_delay_ms: Long,
    val default_macro_keypress_delay_ms: Int
)

data class DiscoveredWorkstation(
    val name: String,
    val ip: String,
    val type: String // "Wi-Fi LAN", "Tailscale", "Recent"
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    val gson = Gson()
    
    var appConfig: AppConfig? = null
    val workspacesList = mutableStateListOf<WorkspaceItemConfig>()
    var cursorHideDelayMs by mutableStateOf(3000L)
    var macroDelayMs by mutableStateOf(120)
    var defaultDiscoveryHostname by mutableStateOf("genesis")
    var defaultDiscoveryDomain by mutableStateOf("genesis.tailscale.net")

    // Remote connection states
    var serverHost by mutableStateOf("10.153.62.48") // Pre-populated workstation LAN IP
    var isConnected by mutableStateOf(false)
    var isConnecting by mutableStateOf(false)
    var explicitlyDisconnected by mutableStateOf(false)
    
    // Live display viewport stream
    var displayBitmap by mutableStateOf<Bitmap?>(null)
    var virtualMousePos by mutableStateOf<Pair<Int, Int>?>(null)

    // Touch control mode: 0 = Touch Absolute (Direct), 1 = Mouse Relative (Trackpad)
    var currentMode by mutableStateOf(0) 
    
    // Modifier Keys States
    var ctrlActive by mutableStateOf(false)
    var altActive by mutableStateOf(false)
    var shiftActive by mutableStateOf(false)
    var superActive by mutableStateOf(false)

    // Network Discovery states
    var discoveredWorkstations by mutableStateOf<List<DiscoveredWorkstation>>(emptyList())
    var isScanning by mutableStateOf(false)

    private val sharedPrefs = application.getSharedPreferences("crimson_deck_prefs", Context.MODE_PRIVATE)
    var showStreamStats by mutableStateOf(sharedPrefs.getBoolean("show_stream_stats", false))
    
    val useFrameDropping = false
    val useWebRtc = false
    var useBackpressure by mutableStateOf(sharedPrefs.getBoolean("use_backpressure", false))
    val useH264Codec = true
    
    var streamFps by mutableStateOf(0)
    var streamBitrateKbps by mutableStateOf(0.0)
    var streamResolution by mutableStateOf("")

    private val frameLock = Any()
    private val lastFrameTimes = mutableListOf<Long>()
    private val lastFrameSizes = mutableListOf<Int>()

    fun saveShowStreamStats(show: Boolean) {
        showStreamStats = show
        sharedPrefs.edit().putBoolean("show_stream_stats", show).apply()
    }

    fun saveUseBackpressure(value: Boolean) {
        useBackpressure = value
        sharedPrefs.edit().putBoolean("use_backpressure", value).apply()
        syncStreamingConfig()
    }

    fun syncStreamingConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val maxAttempts = 3
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                try {
                    val url = URL("http://$serverHost:$serverPort/api/stream/config")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.doOutput = true

                    val payload = gson.toJson(
                        mapOf(
                            "frame_dropping" to useFrameDropping,
                            "transport" to if (useWebRtc) "webrtc" else "websocket",
                            "backpressure" to useBackpressure,
                            "codec" to if (useH264Codec) "h264" else "mjpeg"
                        )
                    )

                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    if (code == 200) {
                        android.util.Log.i("AgentViewModel", "Stream configuration synced successfully (attempt $attempt).")
                        return@launch
                    } else {
                        android.util.Log.w("AgentViewModel", "Stream config sync returned $code (attempt $attempt/$maxAttempts)")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AgentViewModel", "Stream config sync error (attempt $attempt/$maxAttempts): ${e.message}")
                }
                if (attempt < maxAttempts) delay(500L)
            }
            android.util.Log.e("AgentViewModel", "Stream configuration sync failed after $maxAttempts attempts.")
        }
    }

    fun trackFrame(width: Int, height: Int, byteSize: Int) {
        val now = System.currentTimeMillis()
        synchronized(frameLock) {
            lastFrameTimes.add(now)
            lastFrameSizes.add(byteSize)
            
            // Keep only frames from the last 2 seconds
            val cutoff = now - 2000
            while (lastFrameTimes.isNotEmpty() && lastFrameTimes[0] < cutoff) {
                lastFrameTimes.removeAt(0)
                lastFrameSizes.removeAt(0)
            }
            
            val count = lastFrameTimes.size
            if (count >= 2) {
                val durationMs = lastFrameTimes.last() - lastFrameTimes.first()
                if (durationMs > 100) {
                    val seconds = durationMs / 1000.0
                    streamFps = Math.round((count - 1) / seconds).toInt()
                    
                    val totalBytes = lastFrameSizes.sum()
                    streamBitrateKbps = (totalBytes * 8.0 / 1000.0) / seconds
                }
            } else {
                streamFps = 0
                streamBitrateKbps = 0.0
            }
            
            streamResolution = "${width}x${height}"
        }
    }
    var serverPort by mutableStateOf(sharedPrefs.getInt("server_port", 9090))
    var persistentLastHost by mutableStateOf(sharedPrefs.getString("persistent_last_host", "") ?: "")

    fun saveServerPort(port: Int) {
        serverPort = port
        sharedPrefs.edit().putInt("server_port", port).apply()
    }

    fun savePersistentLastHost(host: String) {
        if (host.isNotEmpty()) {
            persistentLastHost = host
            sharedPrefs.edit().putString("persistent_last_host", host).apply()
        }
    }

    var currentKeyboardMode by mutableStateOf(sharedPrefs.getInt("keyboard_mode", 0)) // 0 = Buffered, 1 = Direct

    fun saveKeyboardMode(mode: Int) {
        currentKeyboardMode = mode
        sharedPrefs.edit().putInt("keyboard_mode", mode).apply()
    }

    var repeatKeyDelay by mutableStateOf(sharedPrefs.getInt("repeat_key_delay", 20)) // default 20ms

    fun saveRepeatKeyDelay(delay: Int) {
        repeatKeyDelay = delay
        sharedPrefs.edit().putInt("repeat_key_delay", delay).apply()
    }

    var doubleTapZoomScale by mutableStateOf(sharedPrefs.getFloat("double_tap_zoom_scale", 2.5f)) // default 2.5x

    fun saveDoubleTapZoomScale(scale: Float) {
        doubleTapZoomScale = scale
        sharedPrefs.edit().putFloat("double_tap_zoom_scale", scale).apply()
    }

    var allowedRepeatKeys by mutableStateOf<Set<String>>(
        sharedPrefs.getStringSet("allowed_repeat_keys", setOf("BKSP", "▲", "▼", "◀", "▶", "SPC")) ?: setOf("BKSP", "▲", "▼", "◀", "▶", "SPC")
    )

    fun toggleAllowedRepeatKey(key: String) {
        val currentSet = allowedRepeatKeys.toMutableSet()
        if (currentSet.contains(key)) {
            currentSet.remove(key)
        } else {
            currentSet.add(key)
        }
        allowedRepeatKeys = currentSet
        sharedPrefs.edit().putStringSet("allowed_repeat_keys", allowedRepeatKeys).apply()
    }

    var sentItemsHistory by mutableStateOf<List<String>>(emptyList())

    fun loadSentItemsHistory() {
        val json = sharedPrefs.getString("sent_items_history", null)
        if (json != null) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                sentItemsHistory = gson.fromJson(json, type)
            } catch (e: Exception) {
                sentItemsHistory = emptyList()
            }
        }
    }

    fun addToSentItemsHistory(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val currentList = sentItemsHistory.toMutableList()
        currentList.remove(trimmed)
        currentList.add(0, trimmed)
        if (currentList.size > 30) {
            sentItemsHistory = currentList.take(30)
        } else {
            sentItemsHistory = currentList
        }
        sharedPrefs.edit().putString("sent_items_history", gson.toJson(sentItemsHistory)).apply()
    }

    fun clearHistory() {
        sharedPrefs.edit().remove("history").apply()
        discoveredWorkstations = discoveredWorkstations.filter { it.type != "Recent" }
    }

    fun clearSentItemsHistory() {
        sentItemsHistory = emptyList()
        sharedPrefs.edit().remove("sent_items_history").apply()
    }

    fun removeSingleSentItem(text: String) {
        val trimmed = text.trim()
        val currentList = sentItemsHistory.toMutableList()
        currentList.remove(trimmed)
        sentItemsHistory = currentList
        sharedPrefs.edit().putString("sent_items_history", gson.toJson(sentItemsHistory)).apply()
    }

    // Dynamic Reactive Cyberpunk Theme Colors
    var themePrimary by mutableStateOf(Color(sharedPrefs.getLong("theme_primary", 0xFFFF0055L)))
    var themeSecondary by mutableStateOf(Color(sharedPrefs.getLong("theme_secondary", 0xFF8B002AL)))
    var themeBackground by mutableStateOf(Color(sharedPrefs.getLong("theme_background", 0xFF060509L)))
    var themePanel by mutableStateOf(Color(sharedPrefs.getLong("theme_panel", 0xE616121DL)))

    fun applyTheme(primary: Long, secondary: Long, background: Long, panel: Long) {
        themePrimary = Color(primary)
        themeSecondary = Color(secondary)
        themeBackground = Color(background)
        themePanel = Color(panel)
        
        sharedPrefs.edit()
            .putLong("theme_primary", primary)
            .putLong("theme_secondary", secondary)
            .putLong("theme_background", background)
            .putLong("theme_panel", panel)
            .apply()
    }

    private var lastFramePayloadSize = 0

    var streamWidth by mutableStateOf(1920)
    var streamHeight by mutableStateOf(1080)

    val h264Decoder = com.crimson.deck.data.webrtc.H264Decoder(
        onResolutionChanged = { w, h ->
            streamWidth = w
            streamHeight = h
        },
        onFrameDecoded = { byteSize ->
            trackFrame(streamWidth, streamHeight, byteSize)
        },
        onDecoderRecovered = {
            // Request a new keyframe from server to recover the stream
            android.util.Log.i("AgentViewModel", "Decoder recovered — requesting keyframe from server.")
            requestKeyframe()
        }
    )

    private var reconnectDebounceJob: Job? = null

    fun debouncedReconnect(host: String) {
        reconnectDebounceJob?.cancel()
        reconnectDebounceJob = viewModelScope.launch {
            delay(1500L) // Debounce: wait 1.5s before actually reconnecting
            if (!isConnected && !explicitlyDisconnected) {
                connectToWorkstation(host)
            }
        }
    }

    private val webRtcManager = com.crimson.deck.data.webrtc.WebRtcManager(
        context = application,
        gson = gson,
        onFrameReceived = { data ->
            if (useH264Codec) {
                lastFramePayloadSize = data.size
                h264Decoder.decode(data)
            } else {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap != null) {
                        displayBitmap = bitmap
                        trackFrame(bitmap.width, bitmap.height, data.size)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AgentViewModel", "WebRTC MJPEG frame decode failure: ${e.message}")
                }
            }
        },
        onConnectionStateChanged = { connected ->
            isConnected = connected
            isConnecting = false
            if (connected) {
                ctrlActive = false
                altActive = false
                shiftActive = false
                superActive = false
                saveToHistory(serverHost)
                savePersistentLastHost(serverHost)
                syncStreamingConfig()
            } else {
                synchronized(frameLock) {
                    lastFrameTimes.clear()
                    lastFrameSizes.clear()
                }
                streamFps = 0
                streamBitrateKbps = 0.0
                streamResolution = ""
            }
        }
    )

    private val wsManager = WebSocketManager(
        gson = gson,
        onRawFrameReceived = { data ->
            if (useH264Codec) {
                lastFramePayloadSize = data.size
                h264Decoder.decode(data)
            } else {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap != null) {
                        displayBitmap = bitmap
                        trackFrame(bitmap.width, bitmap.height, data.size)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AgentViewModel", "WebSocket MJPEG frame decode failure: ${e.message}")
                }
            }
        },
        onConnectionStateChanged = { connected ->
            isConnected = connected
            isConnecting = false
            if (connected) {
                ctrlActive = false
                altActive = false
                shiftActive = false
                superActive = false
                saveToHistory(serverHost)
                savePersistentLastHost(serverHost)
                syncStreamingConfig()
            } else {
                synchronized(frameLock) {
                    lastFrameTimes.clear()
                    lastFrameSizes.clear()
                }
                streamFps = 0
                streamBitrateKbps = 0.0
                streamResolution = ""
            }
        }
    )

    var customMacros by mutableStateOf<List<CustomMacro>>(emptyList())

    fun loadMacros() {
        val json = sharedPrefs.getString("custom_macros", null)
        if (json != null) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<CustomMacro>>() {}.type
                customMacros = gson.fromJson(json, type)
            } catch (e: Exception) {
                customMacros = getDefaultMacros()
            }
        } else {
            customMacros = getDefaultMacros()
        }
    }

    fun saveMacro(macro: CustomMacro) {
        val currentList = customMacros.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.name.equals(macro.name, ignoreCase = true) }
        if (existingIndex != -1) {
            currentList[existingIndex] = macro
        } else {
            currentList.add(macro)
        }
        customMacros = currentList
        sharedPrefs.edit().putString("custom_macros", gson.toJson(customMacros)).apply()
    }

    fun deleteMacro(macroName: String) {
        customMacros = customMacros.filter { !it.name.equals(macroName, ignoreCase = true) }
        sharedPrefs.edit().putString("custom_macros", gson.toJson(customMacros)).apply()
    }

    private fun getDefaultMacros(): List<CustomMacro> {
        return listOf(
            CustomMacro("Open Terminal", "ctrl+alt+t", 120),
            CustomMacro("List Files", "l,s,enter", 120),
            CustomMacro("Git Status", "g,i,t,space,s,t,a,t,u,s,enter", 120)
        )
    }

    private fun characterRequiresShift(char: Char): Boolean {
        return char.isUpperCase() || char in "!@#$%^&*()_+{}|:\"<>?~"
    }

    fun executeMacro(macro: CustomMacro) {
        viewModelScope.launch(Dispatchers.IO) {
            val steps = macro.formula.split(",")
            val stepDelay = macro.delayMs.toLong()
            for (step in steps) {
                val trimmedStep = step.trim()
                if (trimmedStep.isEmpty()) continue
                
                if (trimmedStep.contains("+")) {
                    val keys = trimmedStep.split("+").map { it.trim() }
                    val keycodes = mutableListOf<Int>()
                    var needsShift = false
                    
                    for (key in keys) {
                        if (key.length == 1 && characterRequiresShift(key[0])) {
                            needsShift = true
                            val lowerCode = getKeyCodeFromFriendlyName(key.lowercase())
                            if (lowerCode != null) {
                                keycodes.add(lowerCode)
                            }
                        } else {
                            val code = getKeyCodeFromFriendlyName(key.lowercase())
                            if (code != null) {
                                keycodes.add(code)
                            }
                        }
                    }
                    
                    if (needsShift && !keycodes.contains(42)) {
                        // Prepend shift (scancode 42) to modifiers so it is pressed first
                        keycodes.add(0, 42)
                    }
                    
                    // Press down in order
                    for (code in keycodes) {
                        sendKey(code, true)
                        delay(20L)
                    }
                    delay(50L)
                    // Release in reverse order
                    for (code in keycodes.reversed()) {
                        sendKey(code, false)
                        delay(20L)
                    }
                } else {
                    if (trimmedStep.length == 1 && characterRequiresShift(trimmedStep[0])) {
                        // Single shifted character, e.g. "A" or "_" -> emulate shift + key
                        val shiftCode = 42
                        val baseCode = getKeyCodeFromFriendlyName(trimmedStep.lowercase())
                        if (baseCode != null) {
                            sendKey(shiftCode, true)
                            delay(20L)
                            sendKey(baseCode, true)
                            delay(50L)
                            sendKey(baseCode, false)
                            delay(20L)
                            sendKey(shiftCode, false)
                        }
                    } else {
                        val code = getKeyCodeFromFriendlyName(trimmedStep.lowercase())
                        if (code != null) {
                            sendKey(code, true)
                            delay(50L)
                            sendKey(code, false)
                        }
                    }
                }
                delay(stepDelay) // Wait between sequence steps
            }
        }
    }

    fun getKeyCodeFromFriendlyName(name: String): Int? {
        return when (name.lowercase()) {
            "esc" -> 1
            "ctrl" -> 29
            "alt" -> 56
            "tab" -> 15
            "backspace", "bksp" -> 14
            "enter", "ent" -> 28
            "space", "spc" -> 57
            "up", "▲" -> 103
            "down", "▼" -> 108
            "left", "◀" -> 105
            "right", "▶" -> 106
            "shift", "shft" -> 42
            "super", "supr", "win" -> 125
            
            // Numbers
            "0" -> 11
            "1" -> 2
            "2" -> 3
            "3" -> 4
            "4" -> 5
            "5" -> 6
            "6" -> 7
            "7" -> 8
            "8" -> 9
            "9" -> 10
            
            // Alphabet
            "a" -> 30
            "b" -> 48
            "c" -> 46
            "d" -> 32
            "e" -> 18
            "f" -> 33
            "g" -> 34
            "h" -> 35
            "i" -> 23
            "j" -> 36
            "k" -> 37
            "l" -> 38
            "m" -> 50
            "n" -> 49
            "o" -> 24
            "p" -> 25
            "q" -> 16
            "r" -> 19
            "s" -> 31
            "t" -> 20
            "u" -> 22
            "v" -> 47
            "w" -> 17
            "x" -> 45
            "y" -> 21
            "z" -> 44
            
            // Symbols & Punctuation
            "-", "dash", "minus" -> 12
            "_" -> 12
            "=", "equals" -> 13
            "+" -> 13
            "[", "lbracket" -> 26
            "]", "rbracket" -> 27
            "{", "lbrace" -> 26
            "}", "rbrace" -> 27
            ";", "semicolon" -> 39
            ":", "colon" -> 39
            "'", "quote", "apostrophe" -> 40
            "\"", "dquote" -> 40
            "\\", "backslash" -> 43
            "|", "pipe" -> 43
            ",", "comma" -> 51
            ".", "dot", "period" -> 52
            "/", "slash" -> 53
            "<", "lt" -> 51
            ">", "gt" -> 52
            "?", "question" -> 53
            "~", "tilde" -> 41
            "`", "backtick" -> 41
            
            else -> null
        }
    }

    init {
        loadHistory()
        loadMacros()
        loadSentItemsHistory()
        registerDiscoveryReceiver()
    }

    private fun registerDiscoveryReceiver() {
        val filter = IntentFilter("com.crimson.deck.DISCOVER_IP")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val hostName = intent?.getStringExtra("host_name") ?: defaultDiscoveryHostname
                val lanIp = intent?.getStringExtra("lan_ip")
                val tailscaleIp = intent?.getStringExtra("tailscale_ip")
                
                if (!lanIp.isNullOrEmpty()) {
                    addDiscoveredWorkstation(hostName, lanIp, "Wi-Fi LAN")
                }
                if (!tailscaleIp.isNullOrEmpty()) {
                    addDiscoveredWorkstation(hostName, tailscaleIp, "Tailscale")
                }
            }
        }
        getApplication<Application>().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun addDiscoveredWorkstation(name: String, ip: String, type: String) {
        val currentList = discoveredWorkstations.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.ip == ip }
        if (existingIndex == -1) {
            currentList.add(DiscoveredWorkstation(name, ip, type))
        } else {
            currentList[existingIndex] = DiscoveredWorkstation(name, ip, type)
        }
        discoveredWorkstations = currentList
    }

    private fun saveToHistory(ip: String) {
        val historySet = sharedPrefs.getStringSet("history", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!historySet.contains(ip)) {
            historySet.add(ip)
            sharedPrefs.edit().putStringSet("history", historySet).apply()
            loadHistory()
        }
    }

    private fun loadHistory() {
        val historySet = sharedPrefs.getStringSet("history", emptySet()) ?: emptySet()
        val historyList = historySet.map { DiscoveredWorkstation("Recent Device", it, "Recent") }
        
        val currentList = discoveredWorkstations.toMutableList()
        historyList.forEach { recent ->
            if (currentList.none { it.ip == recent.ip }) {
                currentList.add(recent)
            }
        }
        discoveredWorkstations = currentList
    }

    fun connectToWorkstation(host: String) {
        explicitlyDisconnected = false
        serverHost = host
        isConnecting = true
        ctrlActive = false
        altActive = false
        shiftActive = false
        superActive = false
        
        // Release decoder before reconnecting to ensure clean MediaCodec state
        h264Decoder.release()
        synchronized(frameLock) {
            lastFrameTimes.clear()
            lastFrameSizes.clear()
        }
        streamFps = 0
        streamBitrateKbps = 0.0
        streamResolution = ""

        // Sync configuration before connecting to prime the server codec state (H.264 vs MJPEG)
        syncStreamingConfig()
        
        wsManager.disconnect()
        webRtcManager.disconnect()
        if (useWebRtc) {
            webRtcManager.connect(host, serverPort)
        } else {
            wsManager.connectCommand(host, serverPort)
        }
    }

    fun connectStream() {
        if (!useWebRtc) {
            wsManager.connectStream()
        }
    }

    fun disconnectStream() {
        if (!useWebRtc) {
            wsManager.disconnectStream()
        }
    }

    fun requestKeyframe() {
        if (isConnected && !useWebRtc) {
            wsManager.sendCommand("keyframe")
        }
    }

    fun disconnect() {
        explicitlyDisconnected = true
        wsManager.disconnect()
        webRtcManager.disconnect()
        h264Decoder.release()
        isConnected = false
        isConnecting = false
        displayBitmap = null
        synchronized(frameLock) {
            lastFrameTimes.clear()
            lastFrameSizes.clear()
        }
        streamFps = 0
        streamBitrateKbps = 0.0
        streamResolution = ""
    }

    private suspend fun validateAndAddHost(host: String, defaultType: String, defaultName: String) {
        if (host.isEmpty()) return
        try {
            val addresses = withContext(Dispatchers.IO) {
                try {
                    java.net.InetAddress.getAllByName(host)
                } catch (e: Exception) {
                    emptyArray<java.net.InetAddress>()
                }
            }
            for (addr in addresses) {
                val ip = addr.hostAddress ?: continue
                val isTailscale = ip.startsWith("100.")
                val resolvedType = if (isTailscale) "Tailscale" else defaultType
                val resolvedName = if (isTailscale) {
                    if (defaultName.contains("lan")) defaultName.replace("lan", "tailscale") else defaultName
                } else {
                    defaultName
                }
                
                val success = withContext(Dispatchers.IO) {
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(ip, serverPort), 300) // 300ms timeout
                        socket.close()
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                if (success) {
                    withContext(Dispatchers.Main) {
                        addDiscoveredWorkstation(resolvedName, ip, resolvedType)
                    }
                }
            }
        } catch (e: Exception) {
            // DNS resolution or connection failed
        }
    }

    fun scanLocalSubnet() {
        val activeIps = getLocalIpAddresses()
        
        isScanning = true
        // Keep non-scanned items (Recent), clear old Wi-Fi LAN and Tailscale scanned items
        discoveredWorkstations = discoveredWorkstations.filter { it.type != "Wi-Fi LAN" && it.type != "Tailscale" }
        
        viewModelScope.launch(Dispatchers.IO) {
            val jobs = mutableListOf<Job>()
            
            // 1. Resolve MagicDNS from config in parallel
            jobs.add(launch {
                validateAndAddHost(defaultDiscoveryHostname, "Wi-Fi LAN", defaultDiscoveryHostname)
            })
            jobs.add(launch {
                validateAndAddHost(defaultDiscoveryDomain, "Tailscale", "${defaultDiscoveryHostname}-tailscale")
            })
            
            // 2. Resolve persistent last host in parallel
            if (persistentLastHost.isNotEmpty()) {
                jobs.add(launch {
                    val isTs = persistentLastHost.startsWith("100.") || persistentLastHost.contains("tailscale")
                    val defType = if (isTs) "Tailscale" else "Wi-Fi LAN"
                    val defName = if (isTs) "last-host-tailscale" else "last-host-lan"
                    validateAndAddHost(persistentLastHost, defType, defName)
                })
            }
            
            // 3. Scan the standard Wi-Fi subnets (/24) for active interfaces
            for (localIp in activeIps) {
                if (localIp.startsWith("10.") || localIp.startsWith("192.168.") || localIp.startsWith("172.") || localIp.startsWith("100.")) {
                    val lastDot = localIp.lastIndexOf('.')
                    if (lastDot == -1) continue
                    val subnetPrefix = localIp.substring(0, lastDot + 1)
                    
                    val scanSemaphore = Semaphore(16) // Max 16 concurrent TCP probes
                    for (i in 1..254) {
                        val targetIp = "$subnetPrefix$i"
                        if (targetIp == localIp) continue
                        
                        val job = launch {
                            scanSemaphore.withPermit {
                                try {
                                    val socket = Socket()
                                    socket.connect(InetSocketAddress(targetIp, serverPort), 150) // 150ms timeout
                                    socket.close()
                                    withContext(Dispatchers.Main) {
                                        val isTailscale = targetIp.startsWith("100.")
                                        val name = if (isTailscale) "workstation-tailscale" else "workstation-lan"
                                        val type = if (isTailscale) "Tailscale" else "Wi-Fi LAN"
                                        addDiscoveredWorkstation(name, targetIp, type)
                                    }
                                } catch (e: Exception) {
                                    // Offline / Closed port
                                }
                            }
                        }
                        jobs.add(job)
                    }
                }
            }
            
            jobs.joinAll()
            withContext(Dispatchers.Main) {
                isScanning = false
            }
        }
    }

    private fun getLocalIpAddresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null) {
                            list.add(ip)
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return list
    }

    fun sendCommand(type: String, keyCode: Int? = null, pressed: Boolean? = null, 
                    dx: Int? = null, dy: Int? = null, x: Int? = null, y: Int? = null, 
                    maxX: Int? = null, maxY: Int? = null, button: Int? = null, steps: Int? = null,
                    text: String? = null) {
        if (useWebRtc && isConnected) {
            webRtcManager.sendCommand(type, keyCode, pressed, dx, dy, x, y, maxX, maxY, button, steps, text)
        } else {
            wsManager.sendCommand(type, keyCode, pressed, dx, dy, x, y, maxX, maxY, button, steps, text)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Mouse Pointer Command Senders
    // ─────────────────────────────────────────────────────────────────────────────

    fun sendMouseAbsolute(x: Int, y: Int, maxX: Int, maxY: Int) {
        sendCommand(
            type = "mouseabsolute",
            x = x,
            y = y,
            maxX = maxX,
            maxY = maxY
        )
    }

    fun sendMouseRelative(dx: Int, dy: Int) {
        sendCommand(
            type = "mouserelative",
            dx = dx,
            dy = dy
        )
    }

    fun sendMouseClick(button: Int, pressed: Boolean) {
        sendCommand(
            type = "mouseclick",
            button = button,
            pressed = pressed
        )
    }

    fun sendMouseScroll(steps: Int) {
        sendCommand(
            type = "mousescroll",
            steps = steps
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Keyboard Key Scan Code Injections
    // ─────────────────────────────────────────────────────────────────────────────

    fun sendKey(keyCode: Int, pressed: Boolean) {
        sendCommand(
            type = "key",
            keyCode = keyCode,
            pressed = pressed
        )
    }

    fun tapKey(keyCode: Int) {
        sendKey(keyCode, true)
        sendKey(keyCode, false)
    }

    fun sendText(text: String) {
        sendCommand(type = "text", text = text)
    }

    fun sendClipboard(text: String) {
        sendCommand(type = "clipboard", text = text)
    }

    var activeWorkspace by mutableStateOf(1)

    init {
        loadConfig()
    }

    private fun loadConfig() {
        try {
            val configStream = getApplication<Application>().assets.open("config.json")
            val configText = configStream.bufferedReader().use { it.readText() }
            val loadedConfig = gson.fromJson(configText, AppConfig::class.java)
            appConfig = loadedConfig
            
            // Populate workspaces list
            workspacesList.clear()
            workspacesList.addAll(loadedConfig.workspaces)
            
            // Set dynamic properties
            cursorHideDelayMs = loadedConfig.default_cursor_hide_delay_ms
            macroDelayMs = loadedConfig.default_macro_keypress_delay_ms
            defaultDiscoveryHostname = loadedConfig.default_discovery_hostname
            defaultDiscoveryDomain = loadedConfig.default_discovery_domain
            
            // Set default port if not customized inside SharedPreferences yet
            if (!sharedPrefs.contains("server_port")) {
                val parsedPort = loadedConfig.default_network_port.toIntOrNull() ?: 9090
                serverPort = parsedPort
            }
            
            // Override active workspace default
            activeWorkspace = loadedConfig.workspace_options.default_active_id
            
            android.util.Log.i("AgentViewModel", "config.json successfully loaded from assets!")
        } catch (e: Exception) {
            android.util.Log.e("AgentViewModel", "Failed to parse config.json: ${e.message}")
            // Safe fallback
            workspacesList.clear()
            for (i in 1..12) {
                workspacesList.add(WorkspaceItemConfig(i, i.toString(), i.toString()))
            }
        }
    }

    fun sendWorkspaceById(id: Int) {
        val item = workspacesList.find { it.id == id }
        if (item != null) {
            activeWorkspace = id
            sendCommand(type = "workspace", text = item.value)
        }
    }

    fun sendWorkspace(workspaceName: String) {
        val wsNum = workspaceName.toIntOrNull()
        if (wsNum != null && wsNum in 1..12) {
            activeWorkspace = wsNum
        }
        sendCommand(type = "workspace", text = workspaceName)
    }

    fun toggleFullscreen() {
        sendCommand(type = "fullscreen")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Macros Local Export/Import Serializers and Parsers (JSON, TOML, YAML)
    // ─────────────────────────────────────────────────────────────────────────────

    fun exportToYaml(macros: List<CustomMacro>): String {
        val sb = StringBuilder()
        sb.append("macros:\n")
        for (m in macros) {
            sb.append("  - name: \"${m.name.replace("\"", "\\\"")}\"\n")
            sb.append("    formula: \"${m.formula.replace("\"", "\\\"")}\"\n")
            sb.append("    delayMs: ${m.delayMs}\n")
        }
        return sb.toString()
    }

    fun parseYaml(content: String): List<CustomMacro> {
        val macros = mutableListOf<CustomMacro>()
        var currentName = ""
        var currentFormula = ""
        var currentDelay = 120
        
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("- name:") || trimmed.startsWith("name:")) {
                if (currentName.isNotEmpty() && currentFormula.isNotEmpty()) {
                    macros.add(CustomMacro(currentName, currentFormula, currentDelay))
                }
                currentName = trimmed.substringAfter("name:").trim().removeSurrounding("\"").removeSurrounding("'")
                currentFormula = ""
                currentDelay = 120
            } else if (trimmed.startsWith("formula:")) {
                currentFormula = trimmed.substringAfter("formula:").trim().removeSurrounding("\"").removeSurrounding("'")
            } else if (trimmed.startsWith("delayMs:")) {
                currentDelay = trimmed.substringAfter("delayMs:").trim().toIntOrNull() ?: 120
            }
        }
        if (currentName.isNotEmpty() && currentFormula.isNotEmpty()) {
            macros.add(CustomMacro(currentName, currentFormula, currentDelay))
        }
        return macros
    }

    fun exportToToml(macros: List<CustomMacro>): String {
        val sb = StringBuilder()
        for (m in macros) {
            sb.append("[[macros]]\n")
            sb.append("name = \"${m.name.replace("\"", "\\\"")}\"\n")
            sb.append("formula = \"${m.formula.replace("\"", "\\\"")}\"\n")
            sb.append("delayMs = ${m.delayMs}\n\n")
        }
        return sb.toString()
    }

    fun parseToml(content: String): List<CustomMacro> {
        val macros = mutableListOf<CustomMacro>()
        var currentName = ""
        var currentFormula = ""
        var currentDelay = 120
        
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed == "[[macros]]") {
                if (currentName.isNotEmpty() && currentFormula.isNotEmpty()) {
                    macros.add(CustomMacro(currentName, currentFormula, currentDelay))
                }
                currentName = ""
                currentFormula = ""
                currentDelay = 120
            } else if (trimmed.startsWith("name =")) {
                currentName = trimmed.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
            } else if (trimmed.startsWith("formula =")) {
                currentFormula = trimmed.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
            } else if (trimmed.startsWith("delayMs =")) {
                currentDelay = trimmed.substringAfter("=").trim().toIntOrNull() ?: 120
            }
        }
        if (currentName.isNotEmpty() && currentFormula.isNotEmpty()) {
            macros.add(CustomMacro(currentName, currentFormula, currentDelay))
        }
        return macros
    }

    fun importMacrosFromText(content: String, format: String): Boolean {
        try {
            val imported = when (format.lowercase()) {
                "json" -> {
                    val type = object : com.google.gson.reflect.TypeToken<List<CustomMacro>>() {}.type
                    Gson().fromJson<List<CustomMacro>>(content, type)
                }
                "yaml", "yml" -> parseYaml(content)
                "toml" -> parseToml(content)
                else -> return false
            }
            
            if (imported.isNullOrEmpty()) return false
            
            val currentList = customMacros.toMutableList()
            for (imp in imported) {
                if (imp.name.trim().isEmpty() || imp.formula.trim().isEmpty()) continue
                val existingIndex = currentList.indexOfFirst { it.name.equals(imp.name, ignoreCase = true) }
                if (existingIndex != -1) {
                    currentList[existingIndex] = imp
                } else {
                    currentList.add(imp)
                }
            }
            customMacros = currentList
            sharedPrefs.edit().putString("custom_macros", gson.toJson(customMacros)).apply()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Macros Remote Sync HTTP API Calls (POST / GET)
    // ─────────────────────────────────────────────────────────────────────────────

    fun exportMacrosToServer(onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = gson.toJson(customMacros)
                val tomlStr = exportToToml(customMacros)
                val yamlStr = exportToYaml(customMacros)
                
                val payloadMap = mapOf("json" to jsonStr, "toml" to tomlStr, "yaml" to yamlStr)
                val payload = gson.toJson(payloadMap)
                
                val url = URL("http://$serverHost:$serverPort/api/macros/export")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Content-Type", "application/json")
                
                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }
                
                val code = conn.responseCode
                if (code == 200) {
                    withContext(Dispatchers.Main) { onSuccess() }
                } else {
                    withContext(Dispatchers.Main) { onError("Server returned code $code") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Network error") }
            }
        }
    }

    fun importMacrosFromServer(onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("http://$serverHost:$serverPort/api/macros/import")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                
                val code = conn.responseCode
                if (code == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    withContext(Dispatchers.Main) {
                        val imported = importMacrosFromText(response, "json")
                        if (imported) {
                            onSuccess()
                        } else {
                            onError("Failed to parse imported macros")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { onError("Server returned code $code") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Network error") }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Workstation File Sharing & Traverser Integration States
    // ─────────────────────────────────────────────────────────────────────────────

    var currentRemotePath by mutableStateOf("")
    var remoteParentPath by mutableStateOf("")
    var remoteItems by mutableStateOf<List<RemoteFsItem>>(emptyList())
    val remoteSelectedItems = mutableStateListOf<String>()

    var isFileSharingActive by mutableStateOf(false)
    var fileSharingMode by mutableStateOf(0) // 0 = Push (Upload), 1 = Get (Download)
    var isTransferringFiles by mutableStateOf(false)
    var fileTransferProgress by mutableStateOf(0f)

    var pendingUploadUris by mutableStateOf<List<Uri>>(emptyList())
    var isPendingUploadDirectory by mutableStateOf(false)
    var fileTransferProgressText by mutableStateOf("")
    var fileTransferSpeedText by mutableStateOf("")

    private var activeTransferJob: Job? = null
    private var activeConnection: HttpURLConnection? = null

    fun cancelFileTransfer() {
        activeTransferJob?.cancel()
        try {
            activeConnection?.disconnect()
        } catch (e: Exception) {}
        isTransferringFiles = false
        isFileSharingActive = false
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun getSpeedAndBitrateText(bytes: Long, elapsedMs: Long): String {
        if (elapsedMs <= 100) return "Calculating speed..."
        val seconds = elapsedMs / 1000.0
        val bytesPerSec = (bytes / seconds).toLong()
        val bitsPerSec = bytesPerSec * 8

        val speedText = "${formatFileSize(bytesPerSec)}/s"

        val bitrateText = if (bitsPerSec >= 1000000000) {
            String.format("%.1f Gbps", bitsPerSec / 1000000000.0)
        } else if (bitsPerSec >= 1000000) {
            String.format("%.1f Mbps", bitsPerSec / 1000000.0)
        } else if (bitsPerSec >= 1000) {
            String.format("%.1f Kbps", bitsPerSec / 1000.0)
        } else {
            "$bitsPerSec bps"
        }

        return "$speedText ($bitrateText)"
    }

    fun fetchRemoteDirectory(path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val encodedPath = if (path != null) java.net.URLEncoder.encode(path, "UTF-8") else ""
                val url = URL("http://$serverHost:$serverPort/api/fs/list?path=$encodedPath")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val code = conn.responseCode
                if (code == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val type = object : com.google.gson.reflect.TypeToken<RemoteFsResponse>() {}.type
                    val parsed = gson.fromJson<RemoteFsResponse>(response, type)

                    withContext(Dispatchers.Main) {
                        currentRemotePath = parsed.currentPath
                        remoteParentPath = parsed.parentPath
                        remoteItems = parsed.items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                        remoteSelectedItems.clear()
                    }
                } else {
                    android.util.Log.e("AgentViewModel", "Server listing returned code $code")
                }
            } catch (e: Exception) {
                android.util.Log.e("AgentViewModel", "Error fetching remote directory: ${e.message}")
            }
        }
    }

    fun createRemoteDirectory(folderName: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fullPath = if (currentRemotePath.endsWith("/") || currentRemotePath.endsWith("\\")) {
                    currentRemotePath + folderName
                } else {
                    currentRemotePath + "/" + folderName
                }
                val encodedPath = java.net.URLEncoder.encode(fullPath, "UTF-8")
                val url = URL("http://$serverHost:$serverPort/api/fs/mkdir?path=$encodedPath")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val code = conn.responseCode
                if (code == 200) {
                    fetchRemoteDirectory(currentRemotePath)
                    withContext(Dispatchers.Main) {
                        onSuccess()
                    }
                } else {
                    val errMsg = "Server returned code $code"
                    android.util.Log.e("AgentViewModel", errMsg)
                    withContext(Dispatchers.Main) {
                        onError(errMsg)
                    }
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Unknown error"
                android.util.Log.e("AgentViewModel", "Error creating folder: $errMsg")
                withContext(Dispatchers.Main) {
                    onError(errMsg)
                }
            }
        }
    }

    fun uploadSelectedAndroidItems(
        uris: List<Uri>,
        isFolder: Boolean,
        targetHostPath: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        activeTransferJob = viewModelScope.launch(Dispatchers.IO) {
            isTransferringFiles = true
            fileTransferProgress = 0f
            fileTransferProgressText = "Analyzing files..."
            activeConnection = null
            try {
                val boundary = "Boundary-" + System.currentTimeMillis()
                val url = URL("http://$serverHost:$serverPort/api/fs/upload?dest=" + java.net.URLEncoder.encode(targetHostPath, "UTF-8"))
                val conn = url.openConnection() as HttpURLConnection
                activeConnection = conn
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 120000 // Extended timeout for large file sharing uploads
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                val filesToUpload = mutableListOf<UploadFileInfo>()
                val contentResolver = getApplication<Application>().contentResolver

                for (uri in uris) {
                    if (activeTransferJob?.isCancelled == true) throw java.util.concurrent.CancellationException("Cancelled")
                    if (!isFolder) {
                        val doc = DocumentFile.fromSingleUri(getApplication(), uri)
                        if (doc != null && doc.exists()) {
                            filesToUpload.add(UploadFileInfo(uri, doc.name ?: "file", doc.name ?: "file", doc.length()))
                        }
                    } else {
                        val treeDoc = DocumentFile.fromTreeUri(getApplication(), uri)
                        if (treeDoc != null && treeDoc.isDirectory) {
                            addDirFilesRecursive(treeDoc, treeDoc.name ?: "folder", filesToUpload)
                        }
                    }
                }

                if (filesToUpload.isEmpty()) {
                    throw Exception("No files selected to upload")
                }

                conn.outputStream.use { os ->
                    val writer = os.bufferedWriter(Charsets.UTF_8)
                    val totalBytes = filesToUpload.sumOf { it.size }
                    val totalBytesText = formatFileSize(totalBytes)
                    var uploadedBytes = 0L
                    val startTime = System.currentTimeMillis()

                    for (fileInfo in filesToUpload) {
                        if (activeTransferJob?.isCancelled == true) throw java.util.concurrent.CancellationException("Cancelled")
                        writer.write("--$boundary\r\n")
                        writer.write("Content-Disposition: form-data; name=\"files\"; filename=\"${fileInfo.relativePath}\"\r\n")
                        writer.write("Content-Type: application/octet-stream\r\n\r\n")
                        writer.flush()

                        contentResolver.openInputStream(fileInfo.uri)?.use { input ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (activeTransferJob?.isCancelled == true) throw java.util.concurrent.CancellationException("Cancelled")
                                os.write(buffer, 0, read)
                                uploadedBytes += read
                                val elapsedMs = System.currentTimeMillis() - startTime
                                fileTransferSpeedText = getSpeedAndBitrateText(uploadedBytes, elapsedMs)
                                if (totalBytes > 0) {
                                    fileTransferProgress = uploadedBytes.toFloat() / totalBytes.toFloat()
                                    fileTransferProgressText = "${formatFileSize(uploadedBytes)} / $totalBytesText"
                                }
                            }
                        }
                        os.flush()
                        writer.write("\r\n")
                        writer.flush()
                    }
                    writer.write("--$boundary--\r\n")
                    writer.flush()
                }

                val code = conn.responseCode
                if (code == 200) {
                    withContext(Dispatchers.Main) {
                        isFileSharingActive = false
                        onSuccess()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError("Server returned code $code")
                    }
                }
            } catch (e: java.util.concurrent.CancellationException) {
                // Ignore cancellation
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Upload failed")
                }
            } finally {
                isTransferringFiles = false
                activeConnection = null
            }
        }
    }

    private fun addDirFilesRecursive(dir: DocumentFile, currentPath: String, list: MutableList<UploadFileInfo>) {
        val relPrefix = if (currentPath.isEmpty()) "" else "$currentPath/"
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                addDirFilesRecursive(file, "$relPrefix${file.name}", list)
            } else {
                list.add(UploadFileInfo(file.uri, file.name ?: "file", "$relPrefix${file.name}", file.length()))
            }
        }
    }

    fun downloadSelectedHostItems(
        targetHostPaths: List<String>,
        localFolderUri: Uri,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        activeTransferJob = viewModelScope.launch(Dispatchers.IO) {
            isTransferringFiles = true
            fileTransferProgress = 0f
            fileTransferProgressText = "Connecting..."
            activeConnection = null
            try {
                val url = URL("http://$serverHost:$serverPort/api/fs/download")
                val conn = url.openConnection() as HttpURLConnection
                activeConnection = conn
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 120000 // Extended timeout for large zips
                conn.setRequestProperty("Content-Type", "application/json")

                val payloadMap = mapOf("paths" to targetHostPaths)
                val payload = gson.toJson(payloadMap)
                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                if (code != 200) {
                    throw Exception("Server returned code $code")
                }

                val totalLength = conn.contentLengthLong
                val disposition = conn.getHeaderField("Content-Disposition")
                val contentType = conn.contentType ?: ""
                val isZip = contentType.contains("zip") || (disposition != null && disposition.contains(".zip"))

                val parentDoc = DocumentFile.fromTreeUri(getApplication(), localFolderUri)
                    ?: throw Exception("Failed to resolve local folder")

                val contentResolver = getApplication<Application>().contentResolver
                var downloadedBytes = 0L
                val startTime = System.currentTimeMillis()

                conn.inputStream.use { input ->
                    if (isZip) {
                        val zipInput = ZipInputStream(BufferedInputStream(input))
                        var entry: ZipEntry?
                        while (zipInput.nextEntry.also { entry = it } != null) {
                            if (activeTransferJob?.isCancelled == true) throw java.util.concurrent.CancellationException("Cancelled")
                            val entryName = entry!!.name
                            if (entryName.isEmpty()) continue

                            if (entry!!.isDirectory) {
                                createDirectoryPathRecursive(parentDoc, entryName)
                            } else {
                                val parentPath = if (entryName.contains("/")) entryName.substringBeforeLast("/") else ""
                                val fileName = if (entryName.contains("/")) entryName.substringAfterLast("/") else entryName

                                val targetDir = if (parentPath.isNotEmpty()) {
                                    createDirectoryPathRecursive(parentDoc, parentPath)
                                } else {
                                    parentDoc
                                }

                                var fileDoc = targetDir.findFile(fileName)
                                if (fileDoc != null) {
                                    fileDoc.delete()
                                }
                                fileDoc = targetDir.createFile("application/octet-stream", fileName)
                                    ?: throw Exception("Failed to create file $fileName")

                                contentResolver.openOutputStream(fileDoc.uri)?.use { output ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (zipInput.read(buffer).also { read = it } != -1) {
                                        if (activeTransferJob?.isCancelled == true) throw java.util.concurrent.CancellationException("Cancelled")
                                        output.write(buffer, 0, read)
                                        downloadedBytes += read
                                        val elapsedMs = System.currentTimeMillis() - startTime
                                        fileTransferSpeedText = getSpeedAndBitrateText(downloadedBytes, elapsedMs)
                                        if (totalLength > 0) {
                                            fileTransferProgress = downloadedBytes.toFloat() / totalLength.toFloat()
                                            fileTransferProgressText = "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalLength)}"
                                        } else {
                                            fileTransferProgress = 0f
                                            fileTransferProgressText = "Downloaded: ${formatFileSize(downloadedBytes)}"
                                        }
                                    }
                                }
                            }
                            zipInput.closeEntry()
                        }
                    } else {
                        val fileName = if (disposition != null && disposition.contains("filename=")) {
                            disposition.substringAfter("filename=").replace("\"", "")
                        } else {
                            targetHostPaths.first().substringAfterLast("/")
                        }

                        var fileDoc = parentDoc.findFile(fileName)
                        if (fileDoc != null) {
                            fileDoc.delete()
                        }
                        fileDoc = parentDoc.createFile("application/octet-stream", fileName)
                            ?: throw Exception("Failed to create file $fileName")

                        contentResolver.openOutputStream(fileDoc.uri)?.use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (activeTransferJob?.isCancelled == true) throw java.util.concurrent.CancellationException("Cancelled")
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val elapsedMs = System.currentTimeMillis() - startTime
                                fileTransferSpeedText = getSpeedAndBitrateText(downloadedBytes, elapsedMs)
                                if (totalLength > 0) {
                                    fileTransferProgress = downloadedBytes.toFloat() / totalLength.toFloat()
                                    fileTransferProgressText = "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalLength)}"
                                } else {
                                    fileTransferProgress = 0f
                                    fileTransferProgressText = "Downloaded: ${formatFileSize(downloadedBytes)}"
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isFileSharingActive = false
                    onSuccess()
                }
            } catch (e: java.util.concurrent.CancellationException) {
                // Ignore cancellation
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Download failed")
                }
            } finally {
                isTransferringFiles = false
                activeConnection = null
            }
        }
    }

    private fun createDirectoryPathRecursive(parent: DocumentFile, path: String): DocumentFile {
        val parts = path.split("/").filter { it.isNotEmpty() }
        var current = parent
        for (part in parts) {
            val existing = current.findFile(part)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(part) ?: throw Exception("Failed to create directory $part")
            }
        }
        return current
    }

    data class UploadFileInfo(val uri: Uri, val name: String, val relativePath: String, val size: Long)
    data class RemoteFsItem(val name: String, val path: String, val isDir: Boolean, val size: Long)
    data class RemoteFsResponse(val currentPath: String, val parentPath: String, val items: List<RemoteFsItem>)

    override fun onCleared() {
        super.onCleared()
        wsManager.disconnect()
    }
}
