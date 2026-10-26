package com.papahchan.papahnow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.papahchan.papahnow.auth.GfnAuthManager
import com.papahchan.papahnow.diagnostics.CrashDiagnostics
import com.papahchan.papahnow.input.GfnGamepadController
import com.papahchan.papahnow.session.GfnSessionClient
import com.papahchan.papahnow.session.SignalingServerInfo
import com.papahchan.papahnow.signaling.GfnSignalingClient
import com.papahchan.papahnow.ui.NeonBottomTabbar
import com.papahchan.papahnow.ui.NeonCatalog
import com.papahchan.papahnow.ui.NeonCommandPalette
import com.papahchan.papahnow.ui.NeonDiagnosticsCard
import com.papahchan.papahnow.ui.NeonHeroCarousel
import com.papahchan.papahnow.ui.NeonNoir
import com.papahchan.papahnow.ui.NeonRailLandscape
import com.papahchan.papahnow.ui.NeonRailPortrait
import com.papahchan.papahnow.ui.NeonSearchBar
import com.papahchan.papahnow.ui.NeonTopBar
import com.papahchan.papahnow.webrtc.GfnPeerConnection
import com.papahchan.papahnow.webrtc.StreamRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import java.util.UUID

enum class ConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    AUTHENTICATING("Authenticating..."),
    CREATING_SESSION("Creating Session..."),
    SIGNALING("Signaling..."),
    CONNECTING("Connecting WebRTC..."),
    STREAMING("Streaming")
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PapahNowMain"
        private const val STABLE_DEVICE_ID_PREFS = "papahnow_device_id"
        private const val STABLE_DEVICE_ID_KEY = "papahnow_device_id"
        private const val APP_PREFS = "papahnow_prefs"
        private const val CLOUDMATCH_ZONE_KEY = "cloudmatch_zone"
        private const val DEFAULT_CLOUDMATCH_ZONE = "prod"

        // Connection watchdogs: never leave the UI stuck in a connecting state.
        private const val SIGNALING_OFFER_TIMEOUT_MS = 15_000L
        private const val NEGOTIATION_TIMEOUT_MS = 20_000L
        private const val VIDEO_AFTER_ICE_TIMEOUT_MS = 10_000L
    }

    private val authManager = GfnAuthManager()

    /**
     * Stable per-install device id for BOTH the x-device-id header and the
     * deviceHashId body field: generated once, then reused across app restarts.
     */
    private fun persistedDeviceId(): String {
        val prefs = getSharedPreferences(STABLE_DEVICE_ID_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(STABLE_DEVICE_ID_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(STABLE_DEVICE_ID_KEY, fresh).commit()
        return fresh
    }

    /** Cloudmatch base for the selected zone; empty zone falls back to prod. */
    private fun cloudmatchBaseUrl(): String {
        val zone = cloudmatchZone.trim().ifBlank { DEFAULT_CLOUDMATCH_ZONE }
        return "https://$zone.cloudmatchbeta.nvidiagrid.net"
    }

    private var signalingClient: GfnSignalingClient? = null
    // Claim prefetch/PUT context from the last session creation, appended to the
    // banner if the WebSocket handshake fails.
    private var signalingClaimDiagnostics: String = ""
    private var peerConnectionWrapper: GfnPeerConnection? = null
    private var streamRenderer: StreamRenderer? = null

    // Owned by the Activity lifecycle: cancelled on Disconnect/onDestroy so a
    // queued session cannot keep polling and claiming in the background.
    private var connectionJob: Job? = null
    // Fires when signaling/ICE stalls; cancelled as soon as the next expected
    // milestone (offer, ICE connected, first video frame) arrives.
    private var watchdogJob: Job? = null
    // Short-lived, process-independent teardown (Cloudmatch stop) that must be
    // allowed to finish even when the Activity is destroyed.
    private val sessionTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSessionClient: GfnSessionClient? = null
    private var activeAuthToken: String? = null
    private var activeSessionId: String? = null

    // Gamepad controller & state
    private var gamepadController: GfnGamepadController? = null
    private var isGamepadChannelOpen by mutableStateOf(false)
    private var isControlChannelOpen by mutableStateOf(false)
    private var gamepadChannelId by mutableStateOf(-1)
    private var controlChannelId by mutableStateOf(-1)
    private var gamepadPacketsSent by mutableStateOf(0)
    private var gamepadDebugStatus by mutableStateOf("")

    // Compose observable state
    private var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var statusDetail by mutableStateOf("")
    private var errorMessage by mutableStateOf<String?>(null)
    // Persisted crash record from the previous run; cleared from prefs once shown.
    private var lastCrash by mutableStateOf<String?>(null)
    private var authTokenInput by mutableStateOf("")
    private var directSignalingUrlInput by mutableStateOf("")
    private var authCodeInput by mutableStateOf("")
    private var cloudmatchZone by mutableStateOf(DEFAULT_CLOUDMATCH_ZONE)
    private var showDebugPanel by mutableStateOf(false)

    // Neon-Noir 2.0 UI State
    private var activeTab by mutableStateOf("home")
    private var showCommandPalette by mutableStateOf(false)
    private var toastMessage by mutableStateOf<String?>(null)

    private fun showNeonToast(message: String) {
        toastMessage = message
        lifecycleScope.launch {
            delay(3000)
            if (toastMessage == message) {
                toastMessage = null
            }
        }
    }

    private fun getTelemetryDisplay(): String {
        val zone = cloudmatchZone.trim()
        val loc = if (zone.isNotBlank()) zone else "ID-JKT"
        return "$loc • estimasi"
    }

    /**
     * First decoded frame is the only reliable "video is actually flowing"
     * signal: a remote track exists as soon as the offer is applied, long
     * before ICE/DTLS completes.
     */
    private val rendererEvents = object : RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {
            runOnUiThread { onFirstVideoFrameRendered() }
        }

        override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
            Log.i(TAG, "Video frame resolution: ${videoWidth}x$videoHeight rotation=$rotation")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash diagnostics FIRST: even this init code must be covered by the
        // global handler before anything else can throw.
        CrashDiagnostics.install(applicationContext)

        // Zone is persisted so the selected route survives app restarts.
        cloudmatchZone = getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getString(CLOUDMATCH_ZONE_KEY, DEFAULT_CLOUDMATCH_ZONE)
            ?: DEFAULT_CLOUDMATCH_ZONE

        // Read (and clear) what the previous run left behind, so a death without
        // a catchable exception is still visible via the persisted stage record.
        CrashDiagnostics.consumeStage()?.let { lastStage ->
            statusDetail = "Last connect stage: $lastStage"
        }
        CrashDiagnostics.consumeCrash()?.let { lastCrash = it }

        // Initialize PeerConnection and StreamRenderer
        peerConnectionWrapper = GfnPeerConnection(this, peerConnectionListener)
        gamepadController = GfnGamepadController { peerConnectionWrapper }
        streamRenderer = StreamRenderer(this).apply {
            peerConnectionWrapper?.let { pc ->
                initRenderer(pc.eglBase.eglBaseContext, rendererEvents)
            }
        }

        handleAuthIntent(intent)

        setContent {
            PapahNowScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        Log.i(TAG, "Received deep link intent: $uri")
        if (uri.scheme == "nvidia" && uri.host == "auth_redirect") {
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            if (!code.isNullOrEmpty()) {
                Log.i(TAG, "Authorization code received from redirect: $code")
                // Never leave a previous session running when a new login flow
                // is about to create another one.
                if (connectionState != ConnectionState.DISCONNECTED) {
                    disconnectAll()
                }
                authCodeInput = code
                errorMessage = null
                statusDetail = "Auth code received. Exchanging token..."
                startConnectionJob {
                    proceedWithAuthCode(code)
                }
            } else if (!error.isNullOrEmpty()) {
                val errorDesc = uri.getQueryParameter("error_description") ?: error
                Log.e(TAG, "OAuth error from redirect: $errorDesc")
                errorMessage = "Login Error: $errorDesc"
                connectionState = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun startFullConnectionFlow() {
        CrashDiagnostics.setStage("CONNECT_PRESSED")
        // Reconnecting from the debug panel while a session is active must tear
        // the old one down first (including its Cloudmatch allocation).
        if (connectionState != ConnectionState.DISCONNECTED) {
            disconnectAll()
        }
        errorMessage = null
        statusDetail = "Starting connection..."

        // Case 1: Direct Signaling URL provided
        if (directSignalingUrlInput.isNotBlank()) {
            connectDirectSignaling(directSignalingUrlInput.trim())
            return
        }

        // Case 2: Auth Token already present
        if (authTokenInput.isNotBlank()) {
            createSessionAndConnect(authTokenInput.trim())
            return
        }

        // Case 3: Manual Auth Code already present
        if (authCodeInput.isNotBlank()) {
            startConnectionJob {
                proceedWithAuthCode(authCodeInput.trim())
            }
            return
        }

        // Case 4: Launch Browser OAuth2 Flow
        launchOAuthLogin()
    }

    private fun launchOAuthLogin() {
        try {
            connectionState = ConnectionState.AUTHENTICATING
            statusDetail = "Opening browser for NVIDIA login..."
            val authUrl = authManager.createAuthorizationUrl()
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(this, Uri.parse(authUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Custom Tab", e)
            // Fallback: standard ACTION_VIEW
            try {
                val authUrl = authManager.createAuthorizationUrl()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            } catch (fallbackEx: Exception) {
                errorMessage = "Cannot open browser: ${fallbackEx.localizedMessage}"
                connectionState = ConnectionState.DISCONNECTED
            }
        }
    }

    private suspend fun proceedWithAuthCode(code: String) {
        connectionState = ConnectionState.AUTHENTICATING
        statusDetail = "Exchanging auth code for token..."
        val result = authManager.exchangeAuthorizationCode(code)
        result.onSuccess { tokens ->
            // GFNJWT session API expects the GFN client token, not the OAuth access token.
            val sessionToken = tokens.clientToken ?: tokens.accessToken
            authTokenInput = sessionToken
            statusDetail = "Token obtained! Creating session..."
            createSessionAndConnectInternal(sessionToken)
        }.onFailure { ex ->
            Log.e(TAG, "Token exchange failed", ex)
            errorMessage = "Token exchange failed: ${ex.localizedMessage}"
            connectionState = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Tokens must be plain JWT characters ([A-Za-z0-9\-_.]). A corrupted token
     * (e.g. pasted with line breaks or truncated) previously built a malformed
     * request and force-closed the app, so reject it here before any request.
     */
    private fun invalidTokenMessage(token: String): String? {
        for ((index, c) in token.withIndex()) {
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.'
            if (!ok) {
                return "Token mengandung karakter tidak valid di posisi $index ('$c'). Salin ulang token secara utuh tanpa terpotong."
            }
        }
        return null
    }

    private fun createSessionAndConnect(authToken: String) {
        invalidTokenMessage(authToken)?.let { message ->
            Log.e(TAG, "Rejected malformed token before requesting a session")
            errorMessage = message
            connectionState = ConnectionState.DISCONNECTED
            return
        }

        connectionState = ConnectionState.CREATING_SESSION
        statusDetail = "Requesting session from ${cloudmatchBaseUrl()}..."

        startConnectionJob {
            createSessionAndConnectInternal(authToken)
        }
    }

    private suspend fun createSessionAndConnectInternal(authToken: String) {
        activeAuthToken = authToken
        // Built per Connect so a zone change applies without restarting the app.
        val sessionClient = GfnSessionClient(
            cloudmatchUrl = cloudmatchBaseUrl(),
            deviceHashId = persistedDeviceId()
        )
        activeSessionClient = sessionClient
        val result = sessionClient.createStreamingSession(authToken) { progress ->
            runOnUiThread { statusDetail = progress }
        }
        result.onSuccess { serverInfo ->
            activeSessionId = serverInfo.sessionId
            CrashDiagnostics.setStage("CREATE_OK sessionId=${serverInfo.sessionId}")
            statusDetail = "Session created (${serverInfo.sessionId}). Connecting signaling..."
            connectSignaling(serverInfo)
        }.onFailure { ex ->
            Log.e(TAG, "Session creation failed", ex)
            // Stop any allocation that was created before the failure (queue
            // timeout, claim failure, ...) instead of letting it burn quota.
            teardownConnection(
                statusText = "Disconnected",
                errorText = "Session creation failed: ${ex.localizedMessage}",
                recreate = true
            )
        }
    }

    /** Starts (and replaces) the single Activity-owned connection coroutine. */
    private fun startConnectionJob(block: suspend () -> Unit) {
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            block()
        }
    }

    private fun connectDirectSignaling(url: String) {
        try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return
            val port = if (uri.port != -1) uri.port else if (uri.scheme == "wss") 443 else 80
            val secure = uri.scheme == "wss"
            val pairingId = uri.getQueryParameter("pairing_id")

            val info = SignalingServerInfo(
                host = host,
                port = port,
                secure = secure,
                resourcePath = uri.path,
                sessionId = pairingId ?: "poc-session"
            )
            connectSignaling(info)
        } catch (e: Exception) {
            errorMessage = "Invalid direct URL: ${e.localizedMessage}"
            connectionState = ConnectionState.DISCONNECTED
        }
    }

    private fun connectSignaling(serverInfo: SignalingServerInfo) {
        connectionState = ConnectionState.SIGNALING
        statusDetail = "Connecting to signaling server ${serverInfo.host}:${serverInfo.port}..."
        signalingClaimDiagnostics = serverInfo.claimDiagnostics

        try {
            CrashDiagnostics.setStage(
                "SIGNALING_CONNECT_START host=${serverInfo.host}:${serverInfo.port} sessionId=${serverInfo.sessionId}"
            )

            // Ensure PeerConnection is ready
            val wrapper = peerConnectionWrapper
                ?: throw IllegalStateException("PeerConnection wrapper is unavailable")
            if (!wrapper.createPeerConnection(serverInfo.iceServers)) {
                throw IllegalStateException("PeerConnection creation failed")
            }
            CrashDiagnostics.setStage("PEER_CONNECTION_CREATED")
            cancelWatchdog()

            signalingClient?.disconnect()
            signalingClient = GfnSignalingClient(
                host = serverInfo.host,
                port = serverInfo.port,
                secure = serverInfo.secure,
                resourcePath = serverInfo.resourcePath,
                sessionId = serverInfo.sessionId,
                listener = signalingListener
            ).apply {
                connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signaling setup failed", e)
            val claimDetails = signalingClaimDiagnostics
                .takeIf { it.isNotBlank() }
                ?.let { "\n$it" }
                .orEmpty()
            teardownConnection(
                statusText = "Disconnected",
                errorText = "Signaling setup failed: ${e.localizedMessage}$claimDetails",
                recreate = true
            )
        }
    }

    /** ICE counters/states from the active PeerConnection, formatted for the error banner. */
    private fun iceDiagnostics(): String =
        peerConnectionWrapper?.iceDiagnosticSummary()
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n$it" }
            .orEmpty()

    /** Raw signaling RX/TX details for the signaling error banner. */
    private fun signalingIoDetails(): String {
        val client = signalingClient ?: return ""
        return "\nRX: ${client.recentRxSummary()}\nTX answer: ${client.lastTxAnswerSummary()}"
    }

    private val signalingListener = object : GfnSignalingClient.Listener {
        override fun onSignalingConnected() {
            runOnUiThread {
                CrashDiagnostics.setStage("SIGNALING_CONNECTED")
                statusDetail = "Signaling connected. Awaiting SDP offer..."
                startWatchdog(
                    SIGNALING_OFFER_TIMEOUT_MS,
                    "No SDP offer received ${SIGNALING_OFFER_TIMEOUT_MS / 1000}s after signaling connected"
                )
            }
        }

        override fun onOfferReceived(sdp: String, nvstSdp: String?, fromPeerId: Int) {
            runOnUiThread {
                cancelWatchdog()
                val wrapper = peerConnectionWrapper
                if (wrapper == null) {
                    failAndTeardown("SDP offer received but PeerConnection is unavailable")
                    return@runOnUiThread
                }
                connectionState = ConnectionState.CONNECTING
                statusDetail = "SDP offer received. Negotiating WebRTC..."
                wrapper.handleRemoteOffer(sdp, nvstSdp)
                // handleRemoteOffer may have failed synchronously (and torn down).
                if (connectionState == ConnectionState.DISCONNECTED) return@runOnUiThread
                startWatchdog(
                    NEGOTIATION_TIMEOUT_MS,
                    "WebRTC negotiation did not complete within ${NEGOTIATION_TIMEOUT_MS / 1000}s"
                )
            }
        }

        override fun onRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
            runOnUiThread {
                peerConnectionWrapper?.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }
        }

        override fun onSignalingClosed(code: Int, reason: String) {
            runOnUiThread {
                Log.i(TAG, "Signaling closed: $code / $reason")
                // Idempotent: a local disconnect may already have moved us here.
                if (connectionState == ConnectionState.DISCONNECTED) return@runOnUiThread
                val reasonSuffix = reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                val unexpected = code != 1000 && code != 4001
                teardownConnection(
                    statusText = "Signaling finished ($code)$reasonSuffix",
                    errorText = if (unexpected) {
                        "Signaling closed unexpectedly ($code)$reasonSuffix"
                    } else {
                        null
                    },
                    recreate = true
                )
            }
        }

        override fun onSignalingError(t: Throwable) {
            runOnUiThread {
                // A local disconnect/onDestroy may race this failure callback.
                if (connectionState == ConnectionState.DISCONNECTED) return@runOnUiThread
                Log.e(TAG, "Signaling error", t)
                val claimDetails = signalingClaimDiagnostics
                    .takeIf { it.isNotBlank() }
                    ?.let { "\n$it" }
                    .orEmpty()
                val message = "Signaling error: ${t.javaClass.simpleName}: ${t.localizedMessage}" +
                    claimDetails + signalingIoDetails() + iceDiagnostics()
                teardownConnection(
                    statusText = "Disconnected",
                    errorText = message,
                    recreate = true
                )
            }
        }
    }

    private val peerConnectionListener = object : GfnPeerConnection.Listener {
        override fun onSendLocalAnswer(sdp: String, nvstSdp: String?) {
            runOnUiThread {
                statusDetail = "Sending local SDP answer..."
                signalingClient?.sendAnswer(sdp, nvstSdp)
            }
        }

        override fun onSendLocalIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
            runOnUiThread {
                signalingClient?.sendIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }
        }

        override fun onDataChannelStateChange(label: String, id: Int, state: DataChannel.State) {
            runOnUiThread {
                Log.i(TAG, "DataChannel state change: $label[id=$id] -> $state")
                if (label == "input_channel_partially_reliable") {
                    isGamepadChannelOpen = (state == DataChannel.State.OPEN)
                    gamepadChannelId = id
                } else if (label == "control_channel") {
                    isControlChannelOpen = (state == DataChannel.State.OPEN)
                    controlChannelId = id
                }
            }
        }

        override fun onVideoTrackReceived(videoTrack: VideoTrack) {
            runOnUiThread {
                Log.i(TAG, "Remote video track received! Attaching to renderer...")
                streamRenderer?.attachVideoTrack(videoTrack)
                // A track is announced with the SDP answer, before media flows.
                // Keep CONNECTING until the renderer reports its first frame.
                if (connectionState != ConnectionState.STREAMING) {
                    statusDetail = "Video track received. Waiting for first frame..."
                }
            }
        }

        override fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
            runOnUiThread {
                Log.i(TAG, "ICE State changed: $state")
                statusDetail = "ICE: $state"
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    if (connectionState != ConnectionState.STREAMING) {
                        connectionState = ConnectionState.CONNECTING
                        startWatchdog(
                            VIDEO_AFTER_ICE_TIMEOUT_MS,
                            "ICE connected but no video frame within " +
                                "${VIDEO_AFTER_ICE_TIMEOUT_MS / 1000}s"
                        )
                    }
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    if (connectionState != ConnectionState.DISCONNECTED) {
                        failAndTeardown("ICE connection failed${iceDiagnostics()}")
                    }
                }
            }
        }

        override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
            runOnUiThread {
                Log.i(TAG, "PeerConnection state changed: $state")
                statusDetail = "PeerConnection: $state"
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    // STREAMING is only declared when the renderer draws its
                    // first frame; connected transport without frames must
                    // still time out.
                    if (connectionState != ConnectionState.STREAMING) {
                        connectionState = ConnectionState.CONNECTING
                        startWatchdog(
                            VIDEO_AFTER_ICE_TIMEOUT_MS,
                            "PeerConnection connected but no video frame within " +
                                "${VIDEO_AFTER_ICE_TIMEOUT_MS / 1000}s"
                        )
                    }
                } else if (state == PeerConnection.PeerConnectionState.FAILED) {
                    if (connectionState != ConnectionState.DISCONNECTED) {
                        failAndTeardown("WebRTC PeerConnection failed${iceDiagnostics()}")
                    }
                }
            }
        }

        override fun onError(description: String) {
            runOnUiThread {
                if (connectionState == ConnectionState.DISCONNECTED) return@runOnUiThread
                Log.e(TAG, "PeerConnection error: $description")
                failAndTeardown(description + iceDiagnostics())
            }
        }
    }

    /** Called on the UI thread when the renderer draws its first real frame. */
    private fun onFirstVideoFrameRendered() {
        if (connectionState == ConnectionState.DISCONNECTED) return
        cancelWatchdog()
        Log.i(TAG, "First video frame rendered")
        connectionState = ConnectionState.STREAMING
        statusDetail = "Video connected & streaming!"
    }

    /** (Re)arms the single connection watchdog; only one can be pending. */
    private fun startWatchdog(timeoutMs: Long, failureMessage: String) {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(timeoutMs)
            if (connectionState != ConnectionState.DISCONNECTED &&
                connectionState != ConnectionState.STREAMING
            ) {
                Log.e(TAG, "Connection watchdog expired: $failureMessage")
                failAndTeardown(failureMessage + iceDiagnostics())
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun failAndTeardown(message: String) {
        Log.e(TAG, "Tearing down after failure: $message")
        teardownConnection(
            statusText = "Disconnected",
            errorText = message,
            recreate = true
        )
    }

    private fun disconnectAll() {
        teardownConnection(
            statusText = "Disconnected",
            errorText = null,
            recreate = true
        )
    }

    /**
     * Single teardown path for Disconnect, onDestroy, signaling close and
     * watchdog/peer failures. Releases the renderer BEFORE the EGL context it
     * was initialized with, then re-initializes it against the replacement
     * wrapper's context so Connect -> Disconnect -> Connect keeps video.
     */
    private fun teardownConnection(
        statusText: String,
        errorText: String?,
        recreate: Boolean
    ) {
        // Flip state first: late callbacks from the sockets/PeerConnection below
        // then become no-ops instead of starting a second teardown.
        connectionState = ConnectionState.DISCONNECTED
        connectionJob?.cancel()
        connectionJob = null
        cancelWatchdog()

        val client = signalingClient
        signalingClient = null
        client?.disconnect()
        signalingClaimDiagnostics = ""

        stopActiveSession()

        streamRenderer?.cleanUp()
        peerConnectionWrapper?.dispose()
        peerConnectionWrapper = null

        if (recreate) {
            val wrapper = GfnPeerConnection(this, peerConnectionListener)
            peerConnectionWrapper = wrapper
            streamRenderer?.initRenderer(wrapper.eglBase.eglBaseContext, rendererEvents)
            streamRenderer?.clearImage()
        }

        isGamepadChannelOpen = false
        isControlChannelOpen = false
        gamepadChannelId = -1
        controlChannelId = -1
        gamepadPacketsSent = 0
        gamepadDebugStatus = ""

        connectionState = ConnectionState.DISCONNECTED
        statusDetail = statusText
        errorMessage = errorText
    }

    /**
     * Best-effort DELETE of the active Cloudmatch allocation. Runs outside the
     * Activity lifecycle so onDestroy can still finish the request.
     */
    private fun stopActiveSession() {
        val client = activeSessionClient
        val authToken = activeAuthToken
        val sessionId = activeSessionId ?: client?.currentSessionId()
        activeSessionClient = null
        activeAuthToken = null
        activeSessionId = null

        if (client == null || authToken == null || sessionId.isNullOrBlank()) {
            Log.i(TAG, "No active Cloudmatch session to stop")
            return
        }

        sessionTeardownScope.launch {
            client.stopSession(authToken, sessionId)
                .onSuccess { code ->
                    Log.i(TAG, "Cloudmatch session $sessionId stopped (HTTP $code)")
                }
                .onFailure { ex ->
                    Log.w(TAG, "Cloudmatch stop for session $sessionId failed: ${ex.message}")
                }
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepadController?.handleGenericMotionEvent(ev) == true) {
            gamepadPacketsSent = gamepadController?.totalPacketsSent?.get() ?: 0
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepadController?.handleKeyEvent(event) == true) {
            gamepadPacketsSent = gamepadController?.totalPacketsSent?.get() ?: 0
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownConnection(
            statusText = "Disconnected",
            errorText = null,
            recreate = false
        )
    }

    @Composable
    fun PapahNowScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NeonNoir.Ink950)
        ) {
            // Full-screen WebRTC video renderer (hidden when DISCONNECTED to prevent
            // stale frame residue on dashboard; attaches during connection setup before first frame arrives)
            if (connectionState != ConnectionState.DISCONNECTED) {
                streamRenderer?.let { renderer ->
                    AndroidView(
                        factory = { renderer },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (connectionState == ConnectionState.STREAMING) {
                // Streaming HUD Overlay
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter)
                ) {
                    // Top HUD Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xD90A0E1A), RoundedCornerShape(NeonNoir.RadiusMd))
                            .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(NeonNoir.Amber, CircleShape)
                            )
                            Text(
                                text = "PAPAH",
                                color = NeonNoir.Txt,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "NOW",
                                color = NeonNoir.Amber,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        // State Badge (Amber for Streaming — replacing old NVIDIA green)
                        Box(
                            modifier = Modifier
                                .background(Color(0x33FFB224), RoundedCornerShape(NeonNoir.RadiusSm))
                                .border(1.dp, Color(0x73FFB224), RoundedCornerShape(NeonNoir.RadiusSm))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "STREAMING",
                                color = NeonNoir.Amber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    if (statusDetail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = statusDetail,
                            color = NeonNoir.Txt2,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Phase 2 Diagnostics Card (Restyled in Neon-Noir 2.0)
                    NeonDiagnosticsCard(
                        isGamepadOpen = isGamepadChannelOpen,
                        isControlOpen = isControlChannelOpen,
                        gamepadChannelId = gamepadChannelId,
                        controlChannelId = controlChannelId,
                        packetsSent = gamepadPacketsSent,
                        debugStatus = gamepadDebugStatus,
                        onTestGamepadClick = {
                            lifecycleScope.launch {
                                gamepadDebugStatus = "Running debug sequence..."
                                gamepadController?.runDebugSequence { step ->
                                    runOnUiThread {
                                        gamepadDebugStatus = step
                                        gamepadPacketsSent = gamepadController?.totalPacketsSent?.get() ?: 0
                                    }
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action buttons (Disconnect in danger, Debug Options in ghost)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { disconnectAll() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonNoir.Danger,
                                contentColor = NeonNoir.AmberInk
                            ),
                            shape = RoundedCornerShape(NeonNoir.RadiusMd),
                            modifier = Modifier
                                .height(NeonNoir.MinTouchTarget)
                                .weight(1f)
                        ) {
                            Text("Disconnect", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = { showDebugPanel = !showDebugPanel },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonNoir.Ink850,
                                contentColor = NeonNoir.Txt
                            ),
                            shape = RoundedCornerShape(NeonNoir.RadiusMd),
                            modifier = Modifier
                                .height(NeonNoir.MinTouchTarget)
                                .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
                        ) {
                            Text(if (showDebugPanel) "Hide Debug" else "Debug Options", fontSize = 12.sp)
                        }
                    }

                    // Collapsible Debug Panel (when streaming)
                    if (showDebugPanel) {
                        Spacer(modifier = Modifier.height(8.dp))
                        NeonDebugPanel()
                    }
                }
            } else {
                // --- NEON-NOIR 2.0 MAIN DASHBOARD (Disconnected / Connecting) ---
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Top Appbar (Fixed)
                    NeonTopBar(
                        latencyText = getTelemetryDisplay(),
                        onTelemetryClick = {
                            showNeonToast("Server: ${cloudmatchZone.ifBlank { "ID-JKT" }} • Latensi: estimasi (belum diukur)")
                        },
                        onBellClick = {
                            showNeonToast("Notifikasi: Tidak ada antrean.")
                        },
                        onAvatarClick = {
                            showDebugPanel = !showDebugPanel
                        }
                    )

                    // Scrollable Main Content
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Connection / Authentication Progress Banner (if connecting)
                        if (connectionState != ConnectionState.DISCONNECTED) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                        .background(NeonNoir.Ink850, RoundedCornerShape(NeonNoir.RadiusMd))
                                        .border(1.dp, NeonNoir.Amber, RoundedCornerShape(NeonNoir.RadiusMd))
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = connectionState.label,
                                                color = NeonNoir.Amber,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                            if (statusDetail.isNotBlank()) {
                                                Text(
                                                    text = statusDetail,
                                                    color = NeonNoir.Txt2,
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = { disconnectAll() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NeonNoir.Danger,
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(NeonNoir.RadiusSm),
                                            modifier = Modifier.height(NeonNoir.MinTouchTarget)
                                        ) {
                                            Text("Batal", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Error Banner (if error occurred)
                        errorMessage?.let { err ->
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = NeonNoir.Ink800),
                                    shape = RoundedCornerShape(NeonNoir.RadiusMd),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                        .border(1.dp, NeonNoir.Danger, RoundedCornerShape(NeonNoir.RadiusMd))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Error Koneksi",
                                                color = NeonNoir.Danger,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            IconButton(
                                                onClick = { errorMessage = null },
                                                modifier = Modifier.size(NeonNoir.MinTouchTarget)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Close",
                                                    tint = NeonNoir.Txt2,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = err,
                                            color = NeonNoir.Txt,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.heightIn(max = 200.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Crash Banner (if any previous run crash)
                        lastCrash?.let { crash ->
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = NeonNoir.Ink800),
                                    shape = RoundedCornerShape(NeonNoir.RadiusMd),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                        .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Last Crash: $crash",
                                            color = NeonNoir.Txt2,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = { lastCrash = null },
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonNoir.Ink850)
                                        ) {
                                            Text("Tutup", color = NeonNoir.Txt, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Hero Carousel (True Full-Bleed)
                        item {
                            NeonHeroCarousel(
                                onLaunchClick = { slide ->
                                    showNeonToast("Katalog menyusul (Preview: ${slide.title})")
                                },
                                onLibraryClick = {
                                    showNeonToast("Pustaka Cloud — fitur katalog sedang disiapkan.")
                                }
                            )
                        }

                        // Mobile Search Bar Trigger
                        item {
                            Spacer(modifier = Modifier.height(10.dp))
                            NeonSearchBar(
                                onClick = { showCommandPalette = true }
                            )
                        }

                        // Rail 1: Lanjutkan Sesi (Landscape 16:9)
                        item {
                            NeonRailLandscape(
                                title = "Lanjutkan Sesi (Konsep / Preview)",
                                subtitle = "Riwayat Cloud",
                                onGameClick = { game ->
                                    showNeonToast("Katalog menyusul (Preview: ${game.title})")
                                }
                            )
                        }

                        // Rail 2: Performa Tinggi • Rig RTX (Portrait)
                        item {
                            NeonRailPortrait(
                                title = "Performa Tinggi • Rig RTX (Preview)",
                                actionLabel = "Konsep / Preview",
                                items = NeonCatalog.rtxRail,
                                onGameClick = { game ->
                                    showNeonToast("Katalog menyusul (Preview: ${game.title})")
                                }
                            )
                        }

                        // Rail 3: Gratis & Instan Tanpa Antrean (Portrait)
                        item {
                            NeonRailPortrait(
                                title = "Gratis & Instan Tanpa Antrean (Preview)",
                                actionLabel = "Konsep / Preview",
                                items = NeonCatalog.freeRail,
                                onGameClick = { game ->
                                    showNeonToast("Katalog menyusul (Preview: ${game.title})")
                                }
                            )
                        }

                        // Diagnostics Card (Phase 2 Gamepad status & Test Gamepad trigger)
                        item {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Diagnostik Perangkat & Input",
                                    color = NeonNoir.Txt,
                                    fontSize = 16.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.2.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                NeonDiagnosticsCard(
                                    isGamepadOpen = isGamepadChannelOpen,
                                    isControlOpen = isControlChannelOpen,
                                    gamepadChannelId = gamepadChannelId,
                                    controlChannelId = controlChannelId,
                                    packetsSent = gamepadPacketsSent,
                                    debugStatus = gamepadDebugStatus,
                                    onTestGamepadClick = {
                                        lifecycleScope.launch {
                                            gamepadDebugStatus = "Running debug sequence..."
                                            gamepadController?.runDebugSequence { step ->
                                                runOnUiThread {
                                                    gamepadDebugStatus = step
                                                    gamepadPacketsSent = gamepadController?.totalPacketsSent?.get() ?: 0
                                                }
                                            }
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Quick Connect & Debug options row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { startFullConnectionFlow() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NeonNoir.Amber,
                                            contentColor = NeonNoir.AmberInk
                                        ),
                                        shape = RoundedCornerShape(NeonNoir.RadiusMd),
                                        modifier = Modifier
                                            .height(NeonNoir.MinTouchTarget)
                                            .weight(1f)
                                    ) {
                                        Text("Connect Stream", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                    }

                                    Button(
                                        onClick = { showDebugPanel = !showDebugPanel },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NeonNoir.Ink900,
                                            contentColor = NeonNoir.Txt
                                        ),
                                        shape = RoundedCornerShape(NeonNoir.RadiusMd),
                                        modifier = Modifier
                                            .height(NeonNoir.MinTouchTarget)
                                            .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
                                    ) {
                                        Text(if (showDebugPanel) "Hide Overrides" else "Debug Options", fontSize = 12.sp)
                                    }
                                }

                                if (showDebugPanel) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    NeonDebugPanel()
                                }
                            }
                        }

                        // Bottom Spacer for tabbar
                        item {
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }

                    // Bottom Navigation Bar (Fixed)
                    NeonBottomTabbar(
                        activeTab = activeTab,
                        onTabSelect = { tab ->
                            activeTab = tab
                            when (tab) {
                                "home" -> {}
                                "search" -> showCommandPalette = true
                                "library" -> showNeonToast("Pustaka Cloud — fitur katalog sedang disiapkan.")
                                "profile" -> showNeonToast("Profil — Akun Cloud Gaming (Pengaturan & Info Sesi).")
                            }
                        }
                    )
                }
            }

            // Command Palette Sheet
            if (showCommandPalette) {
                NeonCommandPalette(
                    onDismiss = { showCommandPalette = false },
                    onSelectGame = { gameTitle ->
                        showNeonToast("Katalog menyusul (Preview: $gameTitle)")
                    }
                )
            }

            // Floating Toast
            toastMessage?.let { toast ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp)
                        .clip(RoundedCornerShape(NeonNoir.RadiusMd))
                        .background(NeonNoir.Ink800)
                        .border(1.dp, Color(0x8CFFB224), RoundedCornerShape(NeonNoir.RadiusMd))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(NeonNoir.Amber, CircleShape)
                        )
                        Text(
                            text = toast,
                            color = NeonNoir.Txt,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun NeonDebugPanel() {
        Card(
            colors = CardDefaults.cardColors(containerColor = NeonNoir.Ink900),
            shape = RoundedCornerShape(NeonNoir.RadiusMd),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Manual & Debug Overrides",
                    color = NeonNoir.Txt,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Cloudmatch zone routing
                OutlinedTextField(
                    value = cloudmatchZone,
                    onValueChange = { value ->
                        cloudmatchZone = value
                        getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(CLOUDMATCH_ZONE_KEY, value)
                            .apply()
                    },
                    label = { Text("Zone (empty = prod)", color = NeonNoir.Txt3) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NeonNoir.Txt,
                        unfocusedTextColor = NeonNoir.Txt,
                        focusedBorderColor = NeonNoir.Amber,
                        unfocusedBorderColor = NeonNoir.Line,
                        cursorColor = NeonNoir.Amber
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Direct Signaling URL
                OutlinedTextField(
                    value = directSignalingUrlInput,
                    onValueChange = { directSignalingUrlInput = it },
                    label = { Text("Direct Signaling WebSocket URL (wss://...)", color = NeonNoir.Txt3) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NeonNoir.Txt,
                        unfocusedTextColor = NeonNoir.Txt,
                        focusedBorderColor = NeonNoir.Amber,
                        unfocusedBorderColor = NeonNoir.Line,
                        cursorColor = NeonNoir.Amber
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // OAuth Access Token
                OutlinedTextField(
                    value = authTokenInput,
                    onValueChange = { authTokenInput = it },
                    label = { Text("Access Token (Bearer ...)", color = NeonNoir.Txt3) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NeonNoir.Txt,
                        unfocusedTextColor = NeonNoir.Txt,
                        focusedBorderColor = NeonNoir.Amber,
                        unfocusedBorderColor = NeonNoir.Line,
                        cursorColor = NeonNoir.Amber
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // OAuth Code
                OutlinedTextField(
                    value = authCodeInput,
                    onValueChange = { authCodeInput = it },
                    label = { Text("OAuth Code (from redirect)", color = NeonNoir.Txt3) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NeonNoir.Txt,
                        unfocusedTextColor = NeonNoir.Txt,
                        focusedBorderColor = NeonNoir.Amber,
                        unfocusedBorderColor = NeonNoir.Line,
                        cursorColor = NeonNoir.Amber
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            showDebugPanel = false
                            startFullConnectionFlow()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonNoir.Amber,
                            contentColor = NeonNoir.AmberInk
                        ),
                        shape = RoundedCornerShape(NeonNoir.RadiusSm)
                    ) {
                        Text("Apply & Connect", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
