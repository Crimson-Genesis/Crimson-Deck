package com.crimson.deck.data.websocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val gson: Gson,
    private val onRawFrameReceived: (ByteArray) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var commandSocket: WebSocket? = null
    private var streamSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    private var hostAddress: String = ""
    private var hostPort: Int = 9090
    private var isConnected = false
    private var isConnecting = false
    
    private val reconnectRunnable = Runnable { connectCommand(hostAddress, hostPort) }

    fun connectCommand(host: String, port: Int = 9090) {
        if (host.isEmpty()) return
        hostAddress = host
        hostPort = port
        isConnecting = true
        onConnectionStateChanged(false)

        val wsUrlCmd = "ws://$hostAddress:$hostPort/ws"
        Log.i("WebSocketManager", "Connecting to commands at: $wsUrlCmd")

        // Establish Commands WebSocket
        val cmdRequest = Request.Builder().url(wsUrlCmd).build()
        commandSocket = client.newWebSocket(cmdRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("WebSocketManager", "Command socket connected successfully.")
                isConnected = true
                isConnecting = false
                handler.post { onConnectionStateChanged(true) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketManager", "Command socket failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    fun connectStream() {
        if (hostAddress.isEmpty()) return
        if (streamSocket != null) return // Already connected or connecting

        val wsUrlStream = "ws://$hostAddress:$hostPort/stream"
        Log.i("WebSocketManager", "Connecting to video stream at: $wsUrlStream")

        // Establish High-Speed Display Stream WebSocket
        val streamRequest = Request.Builder().url(wsUrlStream).build()
        streamSocket = client.newWebSocket(streamRequest, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val rawBytes = bytes.toByteArray()
                handler.post { onRawFrameReceived(rawBytes) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("WebSocketManager", "Stream socket closed.")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketManager", "Stream socket failure: ${t.message}")
            }
        })
    }

    fun disconnectStream() {
        streamSocket?.close(1000, "Disconnect stream")
        streamSocket = null
        Log.i("WebSocketManager", "Stream socket disconnected manually.")
    }

    private fun handleDisconnect() {
        if (isConnected) {
            isConnected = false
            handler.post { onConnectionStateChanged(false) }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 3000)
    }

    fun sendCommand(type: String, keyCode: Int? = null, pressed: Boolean? = null, 
                    dx: Int? = null, dy: Int? = null, x: Int? = null, y: Int? = null, 
                    maxX: Int? = null, maxY: Int? = null, button: Int? = null, steps: Int? = null,
                    text: String? = null) {
        
        val cmd = mutableMapOf<String, Any>()
        cmd["type"] = type
        if (keyCode != null) cmd["key_code"] = keyCode
        if (pressed != null) cmd["pressed"] = pressed
        if (dx != null) cmd["dx"] = dx
        if (dy != null) cmd["dy"] = dy
        if (x != null) cmd["x"] = x
        if (y != null) cmd["y"] = y
        if (maxX != null) cmd["max_x"] = maxX
        if (maxY != null) cmd["max_y"] = maxY
        if (button != null) cmd["button"] = button
        if (steps != null) cmd["steps"] = steps
        if (text != null) cmd["text"] = text

        val json = gson.toJson(cmd)
        commandSocket?.send(json)
    }

    fun disconnect() {
        handler.removeCallbacks(reconnectRunnable)
        commandSocket?.close(1000, "Disconnect")
        streamSocket?.close(1000, "Disconnect")
        commandSocket = null
        streamSocket = null
        isConnected = false
        isConnecting = false
    }
}
