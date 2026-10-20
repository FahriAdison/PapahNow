package com.papahchan.papahnow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papahchan.papahnow.auth.GfnAuthManager
import com.papahchan.papahnow.diagnostics.CrashDiagnostics
import com.papahchan.papahnow.session.GfnSessionClient
import com.papahchan.papahnow.session.SignalingServerInfo
import com.papahchan.papahnow.signaling.GfnSignalingClient
import com.papahchan.papahnow.webrtc.GfnPeerConnection
import com.papahchan.papahnow.webrtc.StreamRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
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
    }

    private val authManager = GfnAuthManager()
    // Lazy so getSharedPreferences runs after onCreate, not during construction.
    // deviceHashId must be stable per install: CloudMatch sees the device identity
    // as (x-device-id header, deviceHashId body field) and a random-per-process
    // UUID is a plausible trigger for server-side session-state confusion.
    private val sessionClient: GfnSessionClient by lazy {
        GfnSessionClient(deviceHashId = persistedDeviceId())
    }

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

    private var signalingClient: GfnSignalingClient? = null
    private var peerConnectionWrapper: GfnPeerConnection? = null
    private var streamRenderer: StreamRenderer? = null

    // Compose observable state
    private var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var statusDetail by mutableStateOf("")
    private var errorMessage by mutableStateOf<String?>(null)
    // Persisted crash record from the previous run; cleared from prefs once shown.
    private var lastCrash by mutableStateOf<String?>(null)
    private var authTokenInput by mutableStateOf("")
    private var directSignalingUrlInput by mutableStateOf("")
    private var authCodeInput by mutableStateOf("")
    private var showDebugPanel by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash diagnostics FIRST: even this init code must be covered by the
        // global handler before anything else can throw.
        CrashDiagnostics.install(applicationContext)

        // Read (and clear) what the previous run left behind, so a death without
        // a catchable exception is still visible via the persisted stage record.
        CrashDiagnostics.consumeStage()?.let { lastStage ->
            statusDetail = "Last connect stage: $lastStage"
        }
        CrashDiagnostics.consumeCrash()?.let { lastCrash = it }

        // Initialize PeerConnection and StreamRenderer
        peerConnectionWrapper = GfnPeerConnection(this, peerConnectionListener)
        streamRenderer = StreamRenderer(this).apply {
            peerConnectionWrapper?.let { pc ->
                initRenderer(pc.eglBase.eglBaseContext)
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
                authCodeInput = code
                errorMessage = null
                statusDetail = "Auth code received. Exchanging token..."
                CoroutineScope(Dispatchers.Main).launch {
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
            CoroutineScope(Dispatchers.Main).launch {
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
            createSessionAndConnect(sessionToken)
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
        statusDetail = "Requesting session from cloudmatch..."

        CoroutineScope(Dispatchers.Main).launch {
            val result = sessionClient.createStreamingSession(authToken)
            result.onSuccess { serverInfo ->
                CrashDiagnostics.setStage("CREATE_OK sessionId=${serverInfo.sessionId}")
                statusDetail = "Session created (${serverInfo.sessionId}). Connecting signaling..."
                connectSignaling(serverInfo)
            }.onFailure { ex ->
                Log.e(TAG, "Session creation failed", ex)
                errorMessage = "Session creation failed: ${ex.localizedMessage}"
                connectionState = ConnectionState.DISCONNECTED
            }
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

        try {
            CrashDiagnostics.setStage(
                "SIGNALING_CONNECT_START host=${serverInfo.host}:${serverInfo.port} sessionId=${serverInfo.sessionId}"
            )

            // Ensure PeerConnection is ready
            peerConnectionWrapper?.createPeerConnection()
            CrashDiagnostics.setStage("PEER_CONNECTION_CREATED")

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
            errorMessage = "Signaling setup failed: ${e.localizedMessage}"
            connectionState = ConnectionState.DISCONNECTED
        }
    }

    private val signalingListener = object : GfnSignalingClient.Listener {
        override fun onSignalingConnected() {
            runOnUiThread {
                CrashDiagnostics.setStage("SIGNALING_CONNECTED")
                statusDetail = "Signaling connected. Awaiting SDP offer..."
            }
        }

        override fun onOfferReceived(sdp: String, nvstSdp: String?, fromPeerId: Int) {
            runOnUiThread {
                connectionState = ConnectionState.CONNECTING
                statusDetail = "SDP offer received. Negotiating WebRTC..."
                peerConnectionWrapper?.handleRemoteOffer(sdp, nvstSdp)
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
                if (connectionState != ConnectionState.STREAMING) {
                    statusDetail = "Signaling finished ($code)"
                }
            }
        }

        override fun onSignalingError(t: Throwable) {
            runOnUiThread {
                Log.e(TAG, "Signaling error", t)
                errorMessage = "Signaling error: ${t.localizedMessage}"
                connectionState = ConnectionState.DISCONNECTED
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

        override fun onVideoTrackReceived(videoTrack: VideoTrack) {
            runOnUiThread {
                Log.i(TAG, "Remote video track received! Attaching to renderer...")
                streamRenderer?.attachVideoTrack(videoTrack)
                connectionState = ConnectionState.STREAMING
                statusDetail = "Video connected & streaming!"
            }
        }

        override fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
            runOnUiThread {
                Log.i(TAG, "ICE State changed: $state")
                statusDetail = "ICE: $state"
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    if (connectionState != ConnectionState.STREAMING) {
                        connectionState = ConnectionState.CONNECTING
                    }
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    errorMessage = "ICE connection failed"
                    connectionState = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
            runOnUiThread {
                Log.i(TAG, "PeerConnection state changed: $state")
                statusDetail = "PeerConnection: $state"
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    connectionState = ConnectionState.STREAMING
                } else if (state == PeerConnection.PeerConnectionState.FAILED) {
                    errorMessage = "WebRTC PeerConnection failed"
                    connectionState = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onError(description: String) {
            runOnUiThread {
                Log.e(TAG, "PeerConnection error: $description")
                errorMessage = description
                connectionState = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun disconnectAll() {
        signalingClient?.disconnect()
        signalingClient = null
        streamRenderer?.detachVideoTrack()
        peerConnectionWrapper?.dispose()
        peerConnectionWrapper = GfnPeerConnection(this, peerConnectionListener)
        connectionState = ConnectionState.DISCONNECTED
        statusDetail = "Disconnected"
        errorMessage = null
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient?.disconnect()
        streamRenderer?.cleanUp()
        peerConnectionWrapper?.dispose()
    }

    @Composable
    fun PapahNowScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0E1A))
        ) {
            // Full-screen WebRTC video renderer
            streamRenderer?.let { renderer ->
                AndroidView(
                    factory = { renderer },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top Status & Controls Overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "PapahNow",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Phase 1 — Video PoC",
                            color = Color(0xFFFFB224),
                            fontSize = 12.sp
                        )
                    }

                    // Connection State Badge
                    val stateColor = when (connectionState) {
                        ConnectionState.STREAMING -> Color(0xFF4CAF50)
                        ConnectionState.CONNECTING, ConnectionState.SIGNALING, ConnectionState.CREATING_SESSION -> Color(0xFF2196F3)
                        ConnectionState.AUTHENTICATING -> Color(0xFFFF9800)
                        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
                    }

                    Box(
                        modifier = Modifier
                            .background(stateColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = connectionState.label,
                            color = stateColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }

                if (statusDetail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusDetail,
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Error Banner
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xCCB00020)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Error: $error",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        )
                    }
                }

                // Persisted crash banner from the previous run (record is cleared
                // from prefs as soon as it is read; Close just hides the card).
                lastCrash?.let { crash ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xCC4E342E)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Last crash: $crash",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = { lastCrash = null },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8D6E63))
                            ) {
                                Text("Close", color = Color.White)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (connectionState == ConnectionState.DISCONNECTED) {
                        Button(
                            onClick = { startFullConnectionFlow() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF76B900)), // NVIDIA Green
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { disconnectAll() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disconnect", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = { showDebugPanel = !showDebugPanel },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text(if (showDebugPanel) "Hide Debug" else "Debug Options")
                    }
                }

                // Collapsible Debug / Manual Overrides Panel
                if (showDebugPanel) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xEE161B2E)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Manual & Debug Overrides",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Direct Signaling URL
                            OutlinedTextField(
                                value = directSignalingUrlInput,
                                onValueChange = { directSignalingUrlInput = it },
                                label = { Text("Direct Signaling WebSocket URL (wss://...)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF76B900)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // OAuth Access Token
                            OutlinedTextField(
                                value = authTokenInput,
                                onValueChange = { authTokenInput = it },
                                label = { Text("Access Token (Bearer ...)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF76B900)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // OAuth Code
                            OutlinedTextField(
                                value = authCodeInput,
                                onValueChange = { authCodeInput = it },
                                label = { Text("OAuth Code (from redirect)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF76B900)
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
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF76B900))
                                ) {
                                    Text("Apply & Connect", color = Color.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
