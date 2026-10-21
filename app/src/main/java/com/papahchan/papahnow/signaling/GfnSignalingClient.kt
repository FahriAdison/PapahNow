package com.papahchan.papahnow.signaling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GfnSignalingClient(
    private val host: String,
    private val port: Int = 443,
    private val secure: Boolean = true,
    private val resourcePath: String? = null,
    private val sessionId: String? = null,
    private val queryParameters: String? = null,
    private val listener: Listener
) {
    interface Listener {
        fun onSignalingConnected()
        fun onOfferReceived(sdp: String, nvstSdp: String?, fromPeerId: Int)
        fun onRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onSignalingClosed(code: Int, reason: String)
        fun onSignalingError(t: Throwable)
    }

    companion object {
        private const val TAG = "GfnSignaling"

        // WebRTC handshake values from the browser/NanaPlay client.
        private const val SIGNALING_VERSION = "2"
        private const val SIGNALING_ORIGIN = "https://play.geforcenow.com"
        private const val SIGNALING_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36"

        // Proactive client liveness: the native client sends this every 5s.
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_MESSAGE = "{\"hb\":1}"

        // Raw RX ring buffer for the on-screen signaling error banner.
        private const val MAX_RX_MESSAGES = 8
        private const val MAX_RX_MESSAGE_CHARS = 150
    }

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websocket
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private val recentRx = ArrayDeque<String>()
    @Volatile
    private var lastAnswerSummary: String = ""
    private val ackSequence = AtomicInteger(1)
    // Native clients start at 0 and only adopt an id from a peer_info whose name matches peerName.
    private var clientId: Int = 0
    private var serverPeerId: Int = 1 // default server peer id is 1
    // NanaPlay native handshake: 12 random hex chars from a dashless UUID.
    private val peerName = "peer-" + UUID.randomUUID().toString().replace("-", "").take(12)

    fun connect() {
        val scheme = if (secure) "wss" else "ws"
        // WebRTC signaling lives under /nvst; connecting at the root returns HTTP 503.
        // (resourcePath belongs to classic RTSP sessions and is intentionally ignored.)
        val basePath = "/nvst"

        val urlBuilder = StringBuilder("$scheme://$host:$port$basePath/sign_in?peer_id=$peerName&version=$SIGNALING_VERSION&peer_role=1")
        if (!sessionId.isNullOrEmpty()) {
            urlBuilder.append("&pairing_id=").append(sessionId)
        }
        if (!queryParameters.isNullOrEmpty()) {
            urlBuilder.append("&").append(queryParameters)
        }

        val fullUrl = urlBuilder.toString()
        Log.i(TAG, "Connecting to signaling server: $fullUrl (peerName: $peerName)")

        val requestBuilder = Request.Builder().url(fullUrl)
            // Explicit Host without port, matching the native/web GDK handshake.
            .addHeader("Host", host)
            .addHeader("Origin", SIGNALING_ORIGIN)
            .addHeader("User-Agent", SIGNALING_USER_AGENT)
        if (!sessionId.isNullOrEmpty()) {
            // Required for the handshake: the server rejects requests without it.
            requestBuilder.addHeader("Sec-WebSocket-Protocol", "x-nv-sessionid.$sessionId")
        }

        webSocket = okHttpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully (HTTP ${response.code})")
                sendPeerInfo(webSocket)
                startHeartbeat()
                listener.onSignalingConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                recordRecentRx(text)
                Log.d(TAG, "Signaling RECV: $text")
                try {
                    val envelope = jsonConfig.decodeFromString<SignalingEnvelope>(text)

                    // 1. Check Heartbeat
                    if (envelope.hb != null) {
                        Log.d(TAG, "Heartbeat received: ${envelope.hb}")
                        return
                    }

                    // 2. Acknowledge messages that require ack
                    envelope.ackId?.let { ackId ->
                        sendAck(ackId)
                    }

                    // 3. Peer assignment info. The server also sends its own peer_info
                    // (id=1); only adopt an id whose name matches our sign-in peerName.
                    envelope.peerInfo?.let { peerInfo ->
                        if (peerInfo.name == peerName) {
                            clientId = peerInfo.id
                            Log.i(TAG, "Assigned client peer id: $clientId, name: ${peerInfo.name}")
                        } else {
                            Log.i(TAG, "Ignoring peer_info id=${peerInfo.id}, name=${peerInfo.name} (ours=$peerName)")
                        }
                    }

                    // 4. Peer message (Offer / Candidate / etc.)
                    envelope.peerMsg?.let { peerMsg ->
                        serverPeerId = peerMsg.from
                        Log.d(TAG, "Processing peer message from peer ${peerMsg.from} to ${peerMsg.to}")
                        when (val inner = InnerPeerMessage.fromJsonElement(peerMsg.msg)) {
                            is InnerPeerMessage.Offer -> {
                                Log.i(TAG, "SDP OFFER received from peer ${peerMsg.from}! Length: ${inner.sdp.length}, nvstSdp: ${inner.nvstSdp != null}")
                                listener.onOfferReceived(inner.sdp, inner.nvstSdp, peerMsg.from)
                            }
                            is InnerPeerMessage.IceCandidate -> {
                                Log.i(TAG, "Remote ICE candidate: ${inner.sdpMid}[${inner.sdpMLineIndex}] => ${inner.candidate}")
                                listener.onRemoteIceCandidate(inner.sdpMid, inner.sdpMLineIndex, inner.candidate)
                            }
                            is InnerPeerMessage.Answer -> {
                                Log.i(TAG, "SDP Answer received (unexpected on client side)")
                            }
                            is InnerPeerMessage.Unknown -> {
                                Log.w(TAG, "Unknown inner peer message: ${inner.raw}")
                            }
                        }
                    }

                    // 5. Error
                    envelope.error?.let { err ->
                        Log.e(TAG, "Signaling error received: $err")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing signaling message: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Signaling closing: code=$code, reason=$reason")
                webSocket.close(1000, null)
                listener.onSignalingClosed(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Signaling closed: code=$code, reason=$reason")
                stopHeartbeat()
                listener.onSignalingClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling WebSocket failure: ${t.message}, response code: ${response?.code}", t)
                stopHeartbeat()
                listener.onSignalingError(t)
            }
        })
    }

    /**
     * Announces this client peer right after the WebSocket handshake, matching the
     * native client's peer_info. Sent once per connection.
     */
    private fun sendPeerInfo(socket: WebSocket) {
        val ackId = ackSequence.getAndIncrement()
        val message =
            """{"ackid":$ackId,"peer_info":{"browser":"Chrome","browserVersion":"131","connected":true,"id":$clientId,"name":"$peerName","peerRole":0,"resolution":"1280x720","version":2}}"""
        Log.i(TAG, "Signaling SEND PEER_INFO: $message")
        socket.send(message)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val sent = webSocket?.send(HEARTBEAT_MESSAGE) == true
                Log.d(TAG, "Heartbeat sent: $HEARTBEAT_MESSAGE (accepted=$sent)")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** Stores a truncated raw RX message, keeping only the newest eight. */
    private fun recordRecentRx(message: String) {
        synchronized(recentRx) {
            if (recentRx.size >= MAX_RX_MESSAGES) {
                recentRx.removeFirst()
            }
            recentRx.addLast(message.take(MAX_RX_MESSAGE_CHARS))
        }
    }

    /** Raw RX messages for the signaling error banner, oldest to newest. */
    fun recentRxSummary(): String = synchronized(recentRx) {
        recentRx.joinToString(" | ")
    }

    /** Summary of the most recent answer transmitted. */
    fun lastTxAnswerSummary(): String = lastAnswerSummary

    private fun sendAck(ackId: Int) {
        val ackJson = jsonConfig.encodeToString(ClientAck(ack = ackId))
        Log.d(TAG, "Signaling SEND ACK: $ackJson")
        webSocket?.send(ackJson)
    }

    fun sendAnswer(sdp: String, nvstSdp: String?, targetPeerId: Int = serverPeerId) {
        val payload = SdpPayload(
            type = "answer",
            sdp = sdp,
            nvstSdp = nvstSdp
        )
        val payloadString = jsonConfig.encodeToString(payload)
        val wrapper = ClientPeerMessageWrapper(
            peerMsg = PeerMessagePayload(
                from = clientId,
                to = targetPeerId,
                msg = payloadString
            ),
            ackid = ackSequence.getAndIncrement()
        )
        val json = jsonConfig.encodeToString(wrapper)
        Log.i(TAG, "Signaling SEND ANSWER to peer $targetPeerId (SDP length: ${sdp.length})")
        Log.d(TAG, "Signaling ANSWER raw: $json")
        lastAnswerSummary =
            "type=answer sdpLen=${sdp.length} nvstSdpLen=${nvstSdp?.length ?: 0} " +
                "from=$clientId to=$targetPeerId"
        webSocket?.send(json)
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String, targetPeerId: Int = serverPeerId) {
        val payload = IceCandidatePayload(
            sdpMLineIndex = sdpMLineIndex,
            sdpMid = sdpMid,
            candidate = candidate
        )
        val payloadString = jsonConfig.encodeToString(payload)
        val wrapper = ClientPeerMessageWrapper(
            peerMsg = PeerMessagePayload(
                from = clientId,
                to = targetPeerId,
                msg = payloadString
            ),
            ackid = ackSequence.getAndIncrement()
        )
        val json = jsonConfig.encodeToString(wrapper)
        Log.d(TAG, "Signaling SEND ICE Candidate: $candidate")
        webSocket?.send(json)
    }

    fun disconnect() {
        Log.i(TAG, "Disconnecting signaling client...")
        stopHeartbeat()
        try {
            webSocket?.close(1000, "Normal closure")
            webSocket = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing websocket", e)
        }
    }
}
