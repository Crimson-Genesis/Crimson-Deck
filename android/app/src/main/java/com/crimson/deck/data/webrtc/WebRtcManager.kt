package com.crimson.deck.data.webrtc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

class WebRtcManager(
    private val context: Context,
    private val gson: Gson,
    private val onFrameReceived: (ByteArray) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var displayChannel: DataChannel? = null
    private var commandsChannel: DataChannel? = null
    private var isConnected = false
    private var isConnecting = false

    private var hostAddress: String = ""
    private var hostPort: Int = 9090

    init {
        // Initialize WebRTC Android SDK
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("WebRTC-IntelVP8/Enabled/")
                .createInitializationOptions()
        )
    }

    fun connect(host: String, port: Int = 9090) {
        if (host.isEmpty()) return
        hostAddress = host
        hostPort = port
        isConnecting = true
        onConnectionStateChanged(false)

        Log.i("WebRtcManager", "Initializing WebRTC connection to $hostAddress:$hostPort")

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(null, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(null)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d("WebRtcManager", "Signaling state changed: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i("WebRtcManager", "ICE connection state changed: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    isConnected = true
                    isConnecting = false
                    handler.post { onConnectionStateChanged(true) }
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED) {
                    handleDisconnect()
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d("WebRtcManager", "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d("WebRtcManager", "Local ICE candidate: $candidate")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {
                Log.i("WebRtcManager", "Remote added data channel: ${channel.label()}")
            }

            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })

        // Create Data Channels
        val displayInit = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        displayChannel = peerConnection?.createDataChannel("display", displayInit)
        displayChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.i("WebRtcManager", "Display Data Channel state: ${displayChannel?.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    // Trigger the callback to decode MJPEG or H.264
                    onFrameReceived(data)
                }
            }
        })

        val cmdInit = DataChannel.Init().apply {
            ordered = true
        }
        commandsChannel = peerConnection?.createDataChannel("commands", cmdInit)
        commandsChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.i("WebRtcManager", "Commands Data Channel state: ${commandsChannel?.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {}
        })

        // Create local SDP offer
        val mediaConstraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.i("WebRtcManager", "Local SDP set successfully, negotiating with server...")
                        CoroutineScope(Dispatchers.IO).launch {
                            exchangeSdp(sdp.description)
                        }
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRtcManager", "Set local SDP failure: $error")
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e("WebRtcManager", "Set local SDP failure: $error")
                    }
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e("WebRtcManager", "Create offer failure: $error")
            }

            override fun onSetFailure(error: String?) {}
        }, mediaConstraints)
    }

    private suspend fun exchangeSdp(localSdp: String) {
        try {
            val url = URL("http://$hostAddress:$hostPort/offer")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true

            val payloadMap = mapOf("sdp" to localSdp)
            val jsonPayload = gson.toJson(payloadMap)

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(jsonPayload)
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.use { it.readText() }
                val responseMap = gson.fromJson(response, Map::class.java)
                val remoteSdp = responseMap["sdp"] as? String
                if (remoteSdp != null) {
                    withContext(Dispatchers.Main) {
                        setRemoteAnswer(remoteSdp)
                    }
                } else {
                    Log.e("WebRtcManager", "No SDP in response")
                    handleDisconnect()
                }
            } else {
                Log.e("WebRtcManager", "Offer HTTP post returned error: $responseCode")
                handleDisconnect()
            }
        } catch (e: Exception) {
            Log.e("WebRtcManager", "Error exchanging SDP: ${e.message}")
            handleDisconnect()
        }
    }

    private fun setRemoteAnswer(remoteSdp: String) {
        val sDescription = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.i("WebRtcManager", "Remote SDP answer set successfully.")
            }

            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e("WebRtcManager", "Failed to set remote answer: $error")
                handleDisconnect()
            }
        }, sDescription)
    }

    private fun handleDisconnect() {
        if (isConnected || isConnecting) {
            isConnected = false
            isConnecting = false
            handler.post { onConnectionStateChanged(false) }
        }
    }

    fun sendCommand(type: String, keyCode: Int? = null, pressed: Boolean? = null,
                    dx: Int? = null, dy: Int? = null, x: Int? = null, y: Int? = null,
                    maxX: Int? = null, maxY: Int? = null, button: Int? = null, steps: Int? = null,
                    text: String? = null) {

        if (commandsChannel?.state() != DataChannel.State.OPEN) {
            Log.w("WebRtcManager", "Cannot send command: commands data channel not open.")
            return
        }

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
        val buffer = ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8))
        commandsChannel?.send(DataChannel.Buffer(buffer, false))
    }

    fun disconnect() {
        try {
            displayChannel?.close()
            commandsChannel?.close()
            peerConnection?.close()
            peerConnectionFactory?.dispose()
        } catch (e: Exception) {
            Log.e("WebRtcManager", "Error disposing WebRTC: ${e.message}")
        }
        displayChannel = null
        commandsChannel = null
        peerConnection = null
        peerConnectionFactory = null
        isConnected = false
        isConnecting = false
    }
}
