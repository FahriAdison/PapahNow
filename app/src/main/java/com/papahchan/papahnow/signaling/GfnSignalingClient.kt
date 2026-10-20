package com.papahchan.papahnow.signaling

import android.util.Log
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    }

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websocket
        .build()

    private var webSocket: WebSocket? = null
    private val ackSequence = AtomicInteger(1)
    private var clientId: Int = 2 // default client peer id is 2, updated by peer_info
    private var serverPeerId: Int = 1 // default server peer id is 1
    private val peerName = "peer-" + (1000000000L + (Math.random() * 9000000000L).toLong())

    fun connect() {
        val scheme = if (secure) "wss" else "ws"
        // WebRTC signaling lives under /nvst; connecting at the root returns HTTP 503.
        // (resourcePath belongs to classic RTSP sessions and is intentionally ignored.)
        val basePath = "/nvst"

        val urlBuilder = StringBuilder("$scheme://$host:$port$basePath/sign_in?peer_id=$peerName&version=3.0&peer_role=1")
        if (!sessionId.isNullOrEmpty()) {
            urlBuilder.append("&pairing_id=").append(sessionId)
        }
        if (!queryParameters.isNullOrEmpty()) {
            urlBuilder.append("&").append(queryParameters)
        }

        val fullUrl = urlBuilder.toString()
        Log.i(TAG, "Connecting to signaling server: $fullUrl (peerName: $peerName)")

        val requestBuilder = Request.Builder().url(fullUrl)
        if (!sessionId.isNullOrEmpty()) {
            // Match GFN subprotocol pattern: x-nv-sessionid.<sessionId>
            requestBuilder.addHeader("Sec-WebSocket-Protocol", "x-nv-sessionid.$sessionId")
        }

        webSocket = okHttpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully (HTTP ${response.code})")
                listener.onSignalingConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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

                    // 3. Peer assignment info
                    envelope.peerInfo?.let { peerInfo ->
                        clientId = peerInfo.id
                        Log.i(TAG, "Assigned client peer id: $clientId, name: ${peerInfo.name}")
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
                listener.onSignalingClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling WebSocket failure: ${t.message}, response code: ${response?.code}", t)
                listener.onSignalingError(t)
            }
        })
    }

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
        try {
            webSocket?.close(1000, "Normal closure")
            webSocket = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing websocket", e)
        }
    }
}
