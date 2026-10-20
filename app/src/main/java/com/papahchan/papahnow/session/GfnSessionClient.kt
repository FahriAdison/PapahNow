package com.papahchan.papahnow.session

import android.util.Log
import com.papahchan.papahnow.diagnostics.CrashDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class SessionResponse(
    @SerialName("session") val session: SessionData? = null,
    @SerialName("requestStatus") val requestStatus: JsonObject? = null
)

/** Queue/seat provisioning counters from the `session.seatSetupInfo` object. */
@Serializable
data class SeatSetupInfo(
    @SerialName("seatSetupStep") val seatSetupStep: Int? = null,
    // Milliseconds, matching NanaPlay's "est. Ns" formatter.
    @SerialName("seatSetupEta") val seatSetupEta: Long? = null,
    // Some responses nest the queue counter here instead of at the session root.
    @SerialName("queuePosition") val queuePosition: Int? = null
)

@Serializable
data class SessionData(
    // Default "" so a malformed or absent sessionId can never fail the decode;
    // downstream code handles an empty id explicitly instead.
    @SerialName("sessionId") val sessionId: String = "",
    @SerialName("state") val state: String? = null,
    // Numeric session status. 2/3 mean ready (NanaPlay READY_SESSION_STATUSES); the
    // string `state` is unreliable for this.
    @SerialName("status") val status: Int? = null,
    @SerialName("sessionControlInfo") val sessionControlInfo: SessionControlInfo? = null,
    @SerialName("connectionInfo") val connectionInfo: List<ConnectionInfoItem>? = null,
    // Queue telemetry: present while status=1 (queued). queuePosition sits at the
    // session root; seatSetupStep/seatSetupEta are nested under seatSetupInfo.
    @SerialName("queuePosition") val queuePosition: Int? = null,
    @SerialName("seatSetupInfo") val seatSetupInfo: SeatSetupInfo? = null
)

/** Control endpoint of the assigned streaming server; its ip is used for claims. */
@Serializable
data class SessionControlInfo(
    @SerialName("ip") val ip: String? = null
)

@Serializable
data class ConnectionInfoItem(
    // Defaults so a missing/malformed field can never fail the whole decode of
    // the session response; consumers validate before use instead.
    @SerialName("usage") val usage: Int = 0, // 14 = WebSocket Signaling, 2/17 = Media
    // Non-signaling entries can carry a null ip (classic routing), so decode must not
    // fail here; the signaling lookup validates it instead.
    @SerialName("ip") val ip: String? = null,
    @SerialName("port") val port: Int? = 0,
    @SerialName("appLevelProtocol") val appLevelProtocol: Int = 5, // 5 = WSS
    @SerialName("resourcePath") val resourcePath: String? = null
)

data class SignalingServerInfo(
    val host: String,
    val port: Int,
    val secure: Boolean,
    val resourcePath: String?,
    val sessionId: String
)

class GfnSessionClient(
    // Asia-Pacific regional Cloudmatch host, per browser capture. Pass a different
    // host to override.
    private val cloudmatchUrl: String = DEFAULT_CLOUDMATCH_URL,
    // UUID identifying this device. Sent both as the x-device-id header and as the
    // deviceHashId body field, so the two can never diverge. Override with a value
    // persisted per install to keep it stable across app restarts.
    private val deviceHashId: String = UUID.randomUUID().toString()
) {
    companion object {
        private const val TAG = "GfnSessionClient"
        private const val SIGNALING_USAGE_TYPE = 14

        // NanaPlay GfnApi READY_SESSION_STATUSES: session is ready when its numeric
        // status is 2 or 3.
        private val READY_SESSION_STATUSES = setOf(2, 3)
        // Queues routinely run much longer than two minutes: poll every 2s for up
        // to ~30 minutes before giving up. Status=1 is queued, not an error.
        private const val MAX_POLL_ATTEMPTS = 900
        private const val POLL_INTERVAL_MS = 2000L
        // Once the session is ready the zone base can start answering 500 while the
        // claim/resume flow takes over. Five consecutive 500s means the endpoint is
        // wedged, so stop instead of blind-polling for 30 minutes.
        private const val MAX_CONSECUTIVE_POLL_500S = 5

        // BINARY HOST TEST: prod (NanaPlay's default host, GfnApi.kt
        // DEFAULT_STREAMING_SERVICE_URL) instead of the earlier ap-india
        // regional host ("https://ap-india.cloudmatchbeta.nvidiagrid.net")
        // that kept answering HTTP 500 8A910003 on create. If prod also 500s,
        // the transport/allocator mismatch explanation can be ruled out here.
        private const val DEFAULT_CLOUDMATCH_URL = "https://prod.cloudmatchbeta.nvidiagrid.net"

        // Query parameters required by the session request endpoint.
        private const val KEYBOARD_LAYOUT = "en_US"
        private const val LANGUAGE_CODE = "en_US"

        // GFN expects its own JWT scheme for the Authorization header, not Bearer.
        private const val AUTH_SCHEME = "GFNJWT"

        // Body identity, matching the proven NanaPlay client. clientVersion stays "30.0"
        // (Cloudmatch client identity); the Android app version goes in nv-client-version.
        // nv-client-id is NOT a constant: NanaPlay's createSession uses a fresh
        // UUID.randomUUID() per invocation (GfnApi.kt), so the same is generated
        // per Connect flow and shared by create + claim + poll.
        // NanaPlay (proven streaming right now) sends NVIDIA-CLASSIC on every
        // CloudMatch request, including its WebRTC sessions: the transport is
        // selected by the body metadata (GSStreamerType=WebRTC, wssignaling=1),
        // not by this header. WEBRTC here was the only wire-level change between
        // the last known-good create and the 500 8A910003 regression, so we match
        // the proven value. Needs runtime data: if NVIDIA-CLASSIC still yields an
        // RTSP-style resourcePath, the transport decision is further driven by
        // host/zone instead — revisit the host, not this header (see NanaPlay
        // GfnApi.kt CloudMatchDesktopIdentity; their default host is prod, ours
        // was ap-india).
        private const val CLIENT_STREAMER = "NVIDIA-CLASSIC"
        private const val CLIENT_VERSION = "30.0"
        private const val CLIENT_PLATFORM_NAME = "android"
        private const val CLIENT_IDENTIFICATION = "GFN-PC"

        // NanaPlay client identity (proven working), header-only. Must be identical
        // on POST and GET.
        private const val CLIENT_TYPE = "NATIVE"            // nv-client-type
        private const val NV_CLIENT_VERSION = "2.0.80.173"  // nv-client-version
        private const val BROWSER_TYPE = "CHROME"           // nv-browser-type
        private const val DEVICE_OS = "ANDROID"             // nv-device-os
        private const val DEVICE_TYPE = "PHONE"             // nv-device-type
        private const val DEVICE_MAKE = "UNKNOWN"           // nv-device-make
        private const val DEVICE_MODEL = "UNKNOWN"          // nv-device-model
        private const val USER_AGENT = "GFN-PC/22.0 (Android 14) PGC/3.8 (6.36.38319306) okhttp/4.12.0"
        private const val ORIGIN = "https://play.geforcenow.com"   // Origin
        private const val REFERER = "https://play.geforcenow.com/" // Referer
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Builds every Cloudmatch request with the same client identity headers.
     * Always go through this helper so create-session and polling never diverge.
     */
    private fun cloudmatchRequest(
        url: String,
        authToken: String,
        // nv-client-id: fresh UUID per Connect flow (create + claim + poll share it).
        clientId: String
    ): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "$AUTH_SCHEME $authToken")
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Origin", ORIGIN)
            .addHeader("Referer", REFERER)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-device-id", deviceHashId)
            .addHeader("nv-client-id", clientId)
            .addHeader("nv-client-streamer", CLIENT_STREAMER)
            .addHeader("nv-client-version", NV_CLIENT_VERSION)
            .addHeader("nv-client-type", CLIENT_TYPE)
            .addHeader("nv-browser-type", BROWSER_TYPE)
            .addHeader("nv-device-os", DEVICE_OS)
            .addHeader("nv-device-type", DEVICE_TYPE)
            .addHeader("nv-device-make", DEVICE_MAKE)
            .addHeader("nv-device-model", DEVICE_MODEL)

    /** Censors the GFN JWT when logging headers: scheme + short prefix + length only. */
    private fun maskAuthorization(value: String): String {
        val spaceIndex = value.indexOf(' ')
        if (spaceIndex < 0) return "<censored>"
        val scheme = value.substring(0, spaceIndex)
        val token = value.substring(spaceIndex + 1)
        return "$scheme ${token.take(8)}...<censored, ${token.length} chars>"
    }

    /** Formats request headers one per line, with the JWT censored. */
    private fun maskedHeaders(request: Request): String =
        request.headers.joinToString("\n") { (name, value) ->
            val logged = if (name.equals("Authorization", ignoreCase = true)) maskAuthorization(value) else value
            "  $name: $logged"
        }

    /**
     * GFN expects the timezone offset in milliseconds (positive east of UTC), e.g.
     * +25200000 for UTC+7. Computed per request so timezone changes are picked up.
     */
    private fun currentTimezoneOffsetMs(): Int =
        TimeZone.getDefault().getOffset(System.currentTimeMillis())

    /** Signaling connection entry (usage 14), once the server has published it. */
    private fun SessionData.signalingEntry(): ConnectionInfoItem? =
        connectionInfo?.find { it.usage == SIGNALING_USAGE_TYPE }

    /**
     * Ready for claim per NanaPlay ActiveSessionInfo.isReadyForClaim(): numeric
     * status 2/3 AND a non-empty server host. The host comes from claimHost()'s
     * fallback chain (usage=14 ip -> resourcePath host -> sessionControlInfo.ip);
     * a missing usage=14 ip alone must NOT block the claim, because the zone base
     * often publishes only sessionControlInfo/resourcePath until the claim resumes
     * the session. The `state` string is not used for readiness.
     */
    private fun SessionData.isReady(): Boolean =
        status != null && status in READY_SESSION_STATUSES && claimHost() != null

    /**
     * sessionRequestData shared by session creation and claim. NanaPlay's claim body
     * reuses this structure, wrapped in action=2 / data=RESUME.
     */
    private fun buildSessionRequestData(
        appId: Int,
        width: Int,
        height: Int,
        fps: Int
    ): JsonObject {
        val subSessionId = UUID.randomUUID().toString()
        val data = buildJsonObject {
            // AppId as a JSON string like NanaPlay's create body (appId is a
            // decimal string there). Sync builds create and claim from this shared
            // builder, so the claim copy also carries the string parity.
            put("appId", appId.toString())
            put("internalTitle", JsonNull)
            put("audioMode", 2)
            put("remoteControllersBitmap", 0)
            put("sdrHdrMode", 0)
            put("networkTestSessionId", JsonNull)
            put("availableSupportedControllers", buildJsonArray { })
            put("parentSessionId", JsonNull)
            put("clientVersion", CLIENT_VERSION)
            put("deviceHashId", deviceHashId)
            put("clientPlatformName", CLIENT_PLATFORM_NAME)
            put("clientIdentification", CLIENT_IDENTIFICATION)
            put("streamerVersion", 1)
            put("clientTimezoneOffset", currentTimezoneOffsetMs())
            put("surroundAudioInfo", 0)
            put("clientDisplayHdrCapabilities", JsonNull)
            put("accountLinked", true)
            put("userAge", 26)
            put("partnerCustomData", "")
            // NanaPlay CloudMatchDesktopIdentity.PERSIST_GAME_SETTINGS = true.
            put("enablePersistingInGameSettings", true)
            put("secureRTSPSupported", false)
            put("metaData", buildJsonArray {
                add(buildJsonObject {
                    put("key", "SubSessionId")
                    put("value", subSessionId)
                })
                add(buildJsonObject {
                    put("key", "wssignaling")
                    put("value", "1")
                })
                add(buildJsonObject {
                    put("key", "GSStreamerType")
                    put("value", "WebRTC")
                })
                add(buildJsonObject {
                    put("key", "networkType")
                    put("value", "Unknown")
                })
                add(buildJsonObject {
                    put("key", "ClientImeSupport")
                    put("value", "0")
                })
                add(buildJsonObject {
                    put("key", "clientPhysicalResolution")
                    put("value", buildJsonObject {
                        put("horizontalPixels", width)
                        put("verticalPixels", height)
                    }.toString())
                })
                add(buildJsonObject {
                    put("key", "isAppLaunchEnabled")
                    put("value", "True")
                })
                add(buildJsonObject {
                    put("key", "surroundAudioInfo")
                    put("value", "2")
                })
                add(buildJsonObject {
                    put("key", "NanaPlayStreamSettingsSignature")
                    put("value", nanaPlayStreamSettingsSignature(width, height, fps))
                })
            })
            // Parity with NanaPlay monitorSettings() (GfnApi.kt): every monitor
            // entry carries displayData + hdr10PlusGamingData (explicit JsonNull
            // for SDR, exactly as NanaPlay sends) and dpi 100, not 0.
            put("clientRequestMonitorSettings", buildJsonArray {
                add(buildJsonObject {
                    put("monitorId", 0)
                    put("positionX", 0)
                    put("positionY", 0)
                    put("widthInPixels", width)
                    put("heightInPixels", height)
                    put("framesPerSecond", fps)
                    put("sdrHdrMode", 0)
                    put("displayData", JsonNull)
                    put("hdr10PlusGamingData", JsonNull)
                    put("dpi", 100)
                })
            })
            // NanaPlay default GAMEPAD_FRIENDLY (GfnAppLaunchMode): the host
            // provisions its virtual input devices from this value.
            put("appLaunchMode", 2)
            put("sdkVersion", "1.0")
            put("enhancedStreamMode", 1)
            put("useOps", true)
            // Parity with NanaPlay requestedStreamingFeatures() (GfnApi.kt) for
            // our SDR H264 720p60 profile: hdrEnabled=false -> trueHdr=false,
            // bitDepth=0, chromaFormat=0, hdrColorSpace=0; reflex/cloudGsync/l4s
            // off (NanaPlay defaults: enableCloudGsync=false, enableL4S=false,
            // reflex needs fps>=120); hidDevices JsonNull exactly as they send.
            put("requestedStreamingFeatures", buildJsonObject {
                put("reflex", false)
                put("bitDepth", 0)
                put("cloudGsync", false)
                put("enabledL4S", false)
                put("trueHdr", false)
                put("mouseMovementFlags", 0)
                put("supportedHidDevices", 0)
                put("profile", 0)
                put("fallbackToLogicalResolution", false)
                put("hidDevices", JsonNull)
                put("chromaFormat", 0)
                put("prefilterMode", 0)
                put("prefilterSharpness", 0)
                put("prefilterNoiseReduction", 0)
                put("hudStreamingMode", 0)
                put("sdrColorSpace", 2)
                put("hdrColorSpace", 0)
            })
        }
        logSessionRequestData(data)
        return data
    }

    /**
     * Parity for NanaPlay's OPENNOW_STREAM_SETTINGS_METADATA_KEY
     * ("NanaPlayStreamSettingsSignature") written by GfnApi.kt
     * webRtcSessionMetadata() during createSession. Reproduces
     * streamSettingsSessionSignature() (NanaPlay Models.kt:617-633) for our
     * fixed profile: resolution/fps come from the request args; the rest are
     * NanaPlay StreamSettings defaults (Models.kt StreamSettings + withCodecColorCompatibility):
     * maxBitrateMbps=75, codec=H264, colorQuality=TenBit420 (SDR, no 444), and
     * the defaults hdr=0, l4s=0, gsync=0, keyboardLayout="en-US", gameLanguage="en_US".
     */
    private fun nanaPlayStreamSettingsSignature(width: Int, height: Int, fps: Int): String =
        listOf(
            "opennow-android-stream-v1",
            "res=${width}x$height",
            "fps=$fps",
            "bitrate=75",
            "codec=H264",
            "color=TenBit420",
            "hdr=0",
            "l4s=0",
            "gsync=0",
            "keyboard=en-US",
            "language=en_US"
        ).joinToString(";")

    /** Verifies the shared builder really populated every field CloudMatch reads. */
    private fun logSessionRequestData(data: JsonObject) {
        val required = listOf(
            "appId", "accountLinked", "userAge", "clientPlatformName", "clientVersion",
            "deviceHashId", "appLaunchMode", "metaData", "clientRequestMonitorSettings",
            "requestedStreamingFeatures"
        )
        val missing = required.filter { data[it] == null }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "sessionRequestData missing required fields: $missing")
        }
        Log.i(
            TAG,
            "sessionRequestData: accountLinked=${data["accountLinked"]}, userAge=${data["userAge"]}, " +
                "clientPlatformName=${data["clientPlatformName"]}, clientVersion=${data["clientVersion"]}, " +
                "deviceHashId=${data["deviceHashId"]}, appLaunchMode=${data["appLaunchMode"]}, " +
                "keys=${data.keys.size}"
        )
    }

    /** Rejects hosts that would produce an invalid URL (blank, leading/trailing dot, ".."). */
    private fun usableSessionHost(value: String?): String? {
        val host = value?.trim().orEmpty()
        return host.takeIf {
            it.isNotBlank() && !it.startsWith(".") && !it.endsWith(".") && !it.contains("..")
        }
    }

    /** Parses the host out of a resourcePath URL (rtsp/rtsps/wss/https). */
    private fun extractHostFromUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val host = runCatching { URI(raw).host }.getOrNull()
        return usableSessionHost(host)
    }

    /**
     * Host of the assigned streaming server (NanaPlay order): usage=14 ip, then the
     * host parsed from its resourcePath, then sessionControlInfo.ip.
     */
    private fun SessionData.claimHost(): String? {
        val signaling = signalingEntry()
        val signalingIp = signaling?.ip
        val resourcePath = signaling?.resourcePath
        val resourceHost = extractHostFromUrl(resourcePath)
        val controlIp = sessionControlInfo?.ip

        Log.d(
            TAG,
            "Host candidates: signalingIp=$signalingIp (usable=${usableSessionHost(signalingIp)}), " +
                "resourcePath=$resourcePath (host=$resourceHost), " +
                "sessionControlIp=$controlIp (usable=${usableSessionHost(controlIp)})"
        )

        val chosen = usableSessionHost(signalingIp)
            ?: resourceHost
            ?: usableSessionHost(controlIp)
        Log.i(TAG, "Selected session host: $chosen")
        return chosen
    }

    private fun SessionData.effectiveQueuePosition(): Int? =
        queuePosition ?: seatSetupInfo?.queuePosition

    /**
     * Status text for the in-app progress line. Mirrors NanaPlay's queue display:
     * "Queue position N · est. Ns" (seatSetupEta is milliseconds) while queued,
     * and never treats status=1 as an error.
     */
    private fun SessionData.progressText(): String {
        val step = seatSetupInfo?.seatSetupStep
        // NanaPlay hides the queue counter once seatSetupStep reaches 5.
        val position = effectiveQueuePosition()?.takeIf { it > 0 && step != 5 }
        val etaSeconds = seatSetupInfo?.seatSetupEta
            ?.takeIf { it > 0 }
            ?.let { (it + 999) / 1000 }
        return when {
            position != null ->
                "Queue position $position" + (etaSeconds?.let { " · est. ${it}s" } ?: "")
            step == 1 -> "Waiting for a rig"
            status == 0 || status == 1 -> "Checking queue"
            isReady() -> "Session ready (status=$status). Claiming..."
            status != null -> "Session status=$status"
            else -> "Waiting for session..."
        }
    }

    /**
     * Statuses outside {0 unknown, 1 queued, 2/3 ready} mean the allocation is
     * over (e.g. status 4); polling it further can never recover.
     */
    private fun SessionData.isTerminal(): Boolean = status != null && status !in 0..3

    /**
     * Merges a poll response onto the last known session. Queued responses omit
     * connectionInfo/sessionControlInfo, so a plain replace would erase the
     * server host learned from an earlier response. Null fields always keep the
     * previous value; connection entries merge per usage.
     */
    private fun SessionData.mergedWith(fresh: SessionData): SessionData = copy(
        sessionId = fresh.sessionId.ifBlank { sessionId },
        state = fresh.state ?: state,
        status = fresh.status ?: status,
        sessionControlInfo = mergeSessionControlInfo(fresh.sessionControlInfo, sessionControlInfo),
        connectionInfo = mergeConnectionInfo(fresh.connectionInfo, connectionInfo),
        queuePosition = fresh.queuePosition ?: queuePosition,
        seatSetupInfo = mergeSeatSetupInfo(fresh.seatSetupInfo, seatSetupInfo)
    )

    private fun mergeSessionControlInfo(
        fresh: SessionControlInfo?,
        existing: SessionControlInfo?
    ): SessionControlInfo? = when {
        fresh == null -> existing
        existing == null -> fresh
        else -> existing.copy(ip = fresh.ip ?: existing.ip)
    }

    private fun mergeSeatSetupInfo(fresh: SeatSetupInfo?, existing: SeatSetupInfo?): SeatSetupInfo? = when {
        fresh == null -> existing
        existing == null -> fresh
        else -> SeatSetupInfo(
            seatSetupStep = fresh.seatSetupStep ?: existing.seatSetupStep,
            seatSetupEta = fresh.seatSetupEta ?: existing.seatSetupEta,
            queuePosition = fresh.queuePosition ?: existing.queuePosition
        )
    }

    private fun mergeConnectionInfo(
        fresh: List<ConnectionInfoItem>?,
        existing: List<ConnectionInfoItem>?
    ): List<ConnectionInfoItem>? {
        if (fresh == null) return existing
        if (existing == null) return fresh
        val freshByUsage = fresh.associateBy { it.usage }
        val existingUsages = existing.map { it.usage }.toSet()
        val merged = existing.map { old ->
            freshByUsage[old.usage]?.mergedOver(old) ?: old
        }
        return merged + fresh.filter { it.usage !in existingUsages }
    }

    /** Field-level null preservation for a connection entry seen in both responses. */
    private fun ConnectionInfoItem.mergedOver(old: ConnectionInfoItem): ConnectionInfoItem = copy(
        ip = ip ?: old.ip,
        port = port?.takeIf { it > 0 } ?: old.port,
        resourcePath = resourcePath ?: old.resourcePath
    )

    /** Result of the claim attempt, retained for the polling diagnostic banner. */
    private data class ClaimOutcome(
        val host: String,
        val url: String,
        val httpCode: Int?,
        val error: String? = null,
        val responseBody: String = ""
    )

    /**
     * Claims the session once CloudMatch reports it ready (status 2/3 and a
     * usage=14 signaling ip). The signaling endpoint rejects the WebSocket
     * handshake (503) until the session is claimed.
     */
    private fun claimSession(
        host: String,
        sessionId: String,
        authToken: String,
        clientId: String,
        appId: Int,
        width: Int,
        height: Int,
        fps: Int
    ): ClaimOutcome {
        val url = "https://$host/v2/session/$sessionId?keyboardLayout=$KEYBOARD_LAYOUT&languageCode=$LANGUAGE_CODE"
        val payload = buildJsonObject {
            put("action", 2)
            put("data", "RESUME")
            put("sessionRequestData", buildSessionRequestData(appId, width, height, fps))
            put("metaData", buildJsonArray { })
        }.toString()

        Log.i(TAG, "Claiming session $sessionId via $url")
        Log.d(TAG, "PUT $url")
        Log.d(TAG, "Request body: $payload")

        return try {
            val request = cloudmatchRequest(url, authToken, clientId)
                .put(payload.toRequestBody("application/json".toMediaType()))
                .build()
            Log.d(TAG, maskedHeaders(request))
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    CrashDiagnostics.setStage("CLAIM_DONE sessionId=$sessionId")
                    Log.i(TAG, "Session claim accepted: HTTP ${response.code}")
                } else {
                    Log.w(TAG, "Session claim failed: HTTP ${response.code}: $responseBody")
                }
                Log.d(TAG, "Claim response body: $responseBody")
                ClaimOutcome(host = host, url = url, httpCode = response.code, responseBody = responseBody)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session claim request error", e)
            ClaimOutcome(host = host, url = url, httpCode = null, error = e.localizedMessage)
        }
    }

    /**
     * Polling diagnostic in the same labeled style as the create-failure banner:
     * stop reason, create response, claim status, per-poll summary and last poll body.
     */
    private fun buildNoConnectionInfoReport(
        session: SessionData,
        createUrl: String,
        createBody: String,
        claim: ClaimOutcome?,
        claimAttempts: Int,
        pollLines: List<String>,
        lastPollUrl: String,
        lastPollBody: String,
        stopReason: String
    ): String = buildString {
        append("Session not ready: no claimable connectionInfo received\n")
        append("Stop reason: $stopReason\n")
        append(
            "Queue: position=${session.effectiveQueuePosition()}, " +
                "seatSetupStep=${session.seatSetupInfo?.seatSetupStep}, " +
                "seatSetupEta=${session.seatSetupInfo?.seatSetupEta}ms\n"
        )
        append("Session: state=${session.state}, status=${session.status}\n")
        append("Create:\n")
        append("  URL: POST $createUrl\n")
        append("  Response:\n")
        append(createBody).append('\n')
        append("Claim:\n")
        if (claim == null) {
            append("  attempted: no (session never became ready with a server host)\n")
        } else {
            val accepted = claim.httpCode?.let { it in 200..299 } ?: false
            val http = claim.httpCode?.toString() ?: "error: ${claim.error ?: "unknown"}"
            append("  attempts: $claimAttempts, accepted: $accepted\n")
            append("  last host: ${claim.host}, HTTP: $http\n")
            append("  URL: PUT ${claim.url}\n")
            if (!accepted && claim.responseBody.isNotBlank()) {
                append("  Response:\n")
                append(claim.responseBody).append('\n')
            }
        }
        append("Polls:\n")
        if (pollLines.isEmpty()) {
            append("  (no poll responses)\n")
        } else {
            pollLines.forEach { append("  ").append(it).append('\n') }
        }
        append("Last poll:\n")
        append("  URL: GET ${lastPollUrl.ifBlank { "n/a" }}\n")
        append("  Response:\n")
        append(lastPollBody.take(3000))
    }

    /**
     * Requests a game streaming session from Cloudmatch API.
     *
     * @param authToken GFN JWT sent as `Authorization: GFNJWT <token>` (client token, not
     * the OAuth access token).
     */
    suspend fun createStreamingSession(
        authToken: String,
        appId: Int = 107879576, // NTE, from browser capture
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 60,
        // Called on the polling thread with the current queue/claim status text.
        onProgress: (String) -> Unit = {}
    ): Result<SignalingServerInfo> = withContext(Dispatchers.IO) {
        // NanaPlay GfnApi.kt createSession: a fresh random nv-client-id per
        // invocation, shared by create + claim + poll within this Connect flow.
        // The old constant id was in place for a full day and matches the
        // spontaneous ~11:03 regression (suspected server-side dedup/throttle).
        val clientId = UUID.randomUUID().toString()
        val baseEndpoint = "${cloudmatchUrl.trimEnd('/')}/v2/session"
        // Polling stays on the zone base for the whole flow. The assigned server
        // host (claimHost()) is only for the claim PUT: some raw server IPs answer
        // GET /v2/session/{id} with HTTP 500 even after the session is ready.
        val pollBaseEndpoint = cloudmatchUrl.trimEnd('/')
        // Query params required by the session endpoint, per browser capture.
        val endpoint = "$baseEndpoint?keyboardLayout=$KEYBOARD_LAYOUT&languageCode=$LANGUAGE_CODE"
        val payload = buildJsonObject {
            put("sessionRequestData", buildSessionRequestData(appId, width, height, fps))
        }.toString()

        Log.i(TAG, "Requesting session from $endpoint...")
        val requestBody = payload.toRequestBody("application/json".toMediaType())
        val request = cloudmatchRequest(endpoint, authToken, clientId)
            .post(requestBody)
            .build()

        // Full request dump for diffing against browser captures. The JWT is censored.
        Log.d(TAG, "POST $endpoint")
        Log.d(TAG, maskedHeaders(request))
        Log.d(TAG, "Request body: $payload")

        try {
            var response = httpClient.newCall(request).execute()
            var body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                // Surface the exact outgoing request in the UI, not just Logcat, so the
                // 500 can be diffed against NanaPlay/browser captures without a cable.
                val detail = buildString {
                    append("Session creation failed: HTTP ${response.code}\n")
                    append("URL: POST $endpoint\n")
                    append("Headers:\n")
                    append(maskedHeaders(request)).append('\n')
                    append("Request body:\n")
                    append(payload).append('\n')
                    append("Response:\n")
                    append(body)
                }
                Log.e(TAG, detail)
                return@withContext Result.failure(Exception(detail))
            }

            var sessionResp = json.decodeFromString<SessionResponse>(body)
            var session = sessionResp.session ?: return@withContext Result.failure(Exception("No session object in response: $body"))
            // Kept for the polling diagnostic banner (body is reassigned by polls).
            val createResponseBody = body

            Log.i(
                TAG,
                "Initial session (${session.sessionId}): state=${session.state}, " +
                    "status=${session.status}, queuePosition=${session.effectiveQueuePosition()}, " +
                    "signalingIp=${session.signalingEntry()?.ip}"
            )
            onProgress(session.progressText())

            // Claim only once genuinely ready (NanaPlay claimSession semantics):
            // status 2/3 AND a usage=14 signaling ip. Claiming straight after create
            // while status=1 (queued) is rejected with HTTP 400.
            var claimed = false
            var claimOutcome: ClaimOutcome? = null
            var claimAttempts = 0

            var retries = 0
            val pollLines = mutableListOf<String>()
            var lastPollUrl = ""
            var lastPollBody = ""
            var terminalStatus: Int? = null
            var consecutivePoll500s = 0
            var consecutive500Abort = false
            while (retries < MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                retries++

                // Poll the fixed zone base for the entire loop. claimHost() is
                // intentionally NOT used here: switching to the raw server ip made
                // ready-session polls fail with HTTP 500 (v27).
                val pollUrl = "$pollBaseEndpoint/v2/session/${session.sessionId}"
                lastPollUrl = pollUrl
                val pollReq = cloudmatchRequest(pollUrl, authToken, clientId)
                    .get()
                    .build()

                response = httpClient.newCall(pollReq).execute()
                body = response.body?.string() ?: ""
                lastPollBody = body
                if (!response.isSuccessful) {
                    pollLines += "Poll $retries/$MAX_POLL_ATTEMPTS: HTTP ${response.code}, status=n/a, connectionInfo=no, url=$pollUrl"
                    Log.w(TAG, "Poll $retries/$MAX_POLL_ATTEMPTS failed: HTTP ${response.code}: $body")
                    val failedHost = runCatching { URI(pollUrl).host }.getOrNull() ?: pollUrl
                    if (response.code == 500) {
                        consecutivePoll500s++
                        if (consecutivePoll500s >= MAX_CONSECUTIVE_POLL_500S) {
                            consecutive500Abort = true
                            pollLines += "Stopped: $consecutivePoll500s consecutive HTTP 500 poll responses"
                            onProgress("Session poll stopped after $consecutivePoll500s consecutive HTTP 500 @ $failedHost")
                            break
                        }
                    } else {
                        consecutivePoll500s = 0
                    }
                    onProgress("Session poll failed (HTTP ${response.code}) @ $failedHost; retrying...")
                    continue
                }
                consecutivePoll500s = 0

                sessionResp = json.decodeFromString<SessionResponse>(body)
                // Merge: queued responses omit connectionInfo/sessionControlInfo, so a
                // replace would erase a server host learned from an earlier response.
                session = session.mergedWith(sessionResp.session ?: session)
                val connectionSummary = session.connectionInfo?.let { "yes(${it.size})" } ?: "no"
                pollLines += "Poll $retries/$MAX_POLL_ATTEMPTS: HTTP ${response.code}, " +
                    "status=${session.status}, queue=${session.effectiveQueuePosition()}, " +
                    "connectionInfo=$connectionSummary, url=$pollUrl"
                Log.i(
                    TAG,
                    "Poll $retries/$MAX_POLL_ATTEMPTS: state=${session.state}, " +
                        "status=${session.status}, queuePosition=${session.effectiveQueuePosition()}, " +
                        "seatSetupStep=${session.seatSetupInfo?.seatSetupStep}, " +
                        "signalingIp=${session.signalingEntry()?.ip}, ready=${session.isReady()}, " +
                        "claimed=$claimed"
                )

                if (session.isTerminal()) {
                    terminalStatus = session.status
                    pollLines += "Terminal status=${session.status}; polling stopped"
                    onProgress("Session ended (status=${session.status})")
                    break
                }

                if (!session.isReady()) {
                    onProgress(session.progressText())
                } else {
                    // Ready: stop polling and claim now (NanaPlay order). A 2xx claim
                    // is final — proceed straight to signaling with this ready session
                    // instead of polling the zone base again.
                    val host = session.claimHost()
                    if (host == null) {
                        pollLines += "Claim skipped: ready but no usable server host"
                        onProgress("Session ready but no server host yet; retrying...")
                    } else {
                        claimAttempts++
                        val outcome = claimSession(
                            host,
                            session.sessionId,
                            authToken,
                            clientId,
                            appId,
                            width,
                            height,
                            fps
                        )
                        claimOutcome = outcome
                        val accepted = outcome.httpCode?.let { it in 200..299 } ?: false
                        val http = outcome.httpCode?.toString() ?: (outcome.error ?: "error")
                        pollLines += "Claim #$claimAttempts: HTTP $http, accepted=$accepted, " +
                            "host=$host, url=${outcome.url}"
                        if (accepted) {
                            claimed = true
                            onProgress("Session claimed (HTTP ${outcome.httpCode}). Connecting signaling...")
                            break
                        } else {
                            // Claim not ready/rejected: keep polling and retry the claim
                            // when the session is ready again.
                            onProgress("Claim failed ($http); continuing polling...")
                        }
                    }
                }
            }

            if (session.isReady()) {
                CrashDiagnostics.setStage("POLL_READY status=${session.status}")
            }
            if (claimed) {
                CrashDiagnostics.setStage("CLAIMED sessionId=${session.sessionId}")
            }
            Log.i(
                TAG,
                "Polling ended after $retries attempt(s): state=${session.state}, " +
                    "status=${session.status}, queuePosition=${session.effectiveQueuePosition()}, " +
                    "signalingIp=${session.signalingEntry()?.ip}, claimed=$claimed, " +
                    "terminalStatus=$terminalStatus"
            )

            // A queued session is never an error. The final failure is a terminal
            // allocation, five consecutive HTTP 500 polls, or an exhausted budget.
            if (terminalStatus != null || !session.isReady() || !claimed) {
                val stopReason = when {
                    terminalStatus != null -> "terminal status=$terminalStatus"
                    consecutive500Abort ->
                        "$consecutivePoll500s consecutive HTTP 500 poll responses " +
                            "(claim outcome and last 500 body below)"
                    else -> "polling limit reached after $retries attempt(s)"
                }
                return@withContext Result.failure(
                    Exception(
                        buildNoConnectionInfoReport(
                            session = session,
                            createUrl = endpoint,
                            createBody = createResponseBody,
                            claim = claimOutcome,
                            claimAttempts = claimAttempts,
                            pollLines = pollLines,
                            lastPollUrl = lastPollUrl,
                            lastPollBody = lastPollBody,
                            stopReason = stopReason
                        )
                    )
                )
            }

            // Raw connectionInfo (including fields we don't model) so classic-routing
            // responses can be inspected directly in Logcat.
            val rawConnectionInfo = runCatching {
                json.parseToJsonElement(body).jsonObject["session"]?.jsonObject?.get("connectionInfo")
            }.getOrNull()
            Log.i(TAG, "Raw connectionInfo: $rawConnectionInfo")

            val connectionList = session.connectionInfo
                ?: return@withContext Result.failure(
                    Exception(
                        buildNoConnectionInfoReport(
                            session = session,
                            createUrl = endpoint,
                            createBody = createResponseBody,
                            claim = claimOutcome,
                            claimAttempts = claimAttempts,
                            pollLines = pollLines,
                            lastPollUrl = lastPollUrl,
                            lastPollBody = lastPollBody,
                            stopReason = "ready but connectionInfo is missing after merge"
                        )
                    )
                )

            Log.i(TAG, "connectionInfo: $connectionList")

            val signalingInfo = connectionList.find { it.usage == SIGNALING_USAGE_TYPE }
                ?: return@withContext Result.failure(Exception("No signaling server (usage=$SIGNALING_USAGE_TYPE) found in connectionInfo: $connectionList"))

            // Validated with the same fallback chain as the claim host: a malformed
            // usage=14 ip (e.g. ".cloudmatchbeta...") must not reach OkHttp as a URL.
            val host = usableSessionHost(signalingInfo.ip)
                ?: session.claimHost()
                ?: return@withContext Result.failure(Exception("No usable signaling host in connectionInfo: $connectionList"))
            // GFN signaling is always WSS regardless of appLevelProtocol (usage=14
            // entries carry 6, not 5): NanaPlay hardcodes wss:// and the HTTPS web
            // client cannot open ws://. Do not relax the network security policy.
            val secure = true
            // The WebSocket signaling entrypoint is always 443. connectionInfo's
            // usage=14 port (e.g. 48322) belongs to a different transport and routed
            // the handshake to a 404.
            val signalingPort = 443
            Log.i(TAG, "Signaling server found: host=$host, port=$signalingPort, secure=$secure, resourcePath=${signalingInfo.resourcePath}")

            Result.success(
                SignalingServerInfo(
                    host = host,
                    port = signalingPort,
                    secure = secure,
                    resourcePath = signalingInfo.resourcePath,
                    sessionId = session.sessionId
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during session creation", e)
            Result.failure(e)
        }
    }
}
