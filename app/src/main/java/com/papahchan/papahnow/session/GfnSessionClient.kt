package com.papahchan.papahnow.session

import android.util.Log
import com.papahchan.papahnow.diagnostics.CrashDiagnostics
import com.papahchan.papahnow.network.GfnHttpClients
import com.papahchan.papahnow.signaling.IceServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.TimeZone
import java.util.UUID

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
    val sessionId: String,
    // Claim prefetch/PUT details, surfaced in the error banner if signaling fails.
    val claimDiagnostics: String = "",
    // ICE servers published by the claim response (may be empty; caller falls back).
    val iceServers: List<IceServerConfig> = emptyList()
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

    // Shared process-wide client: no per-Connect dispatcher/connection-pool leak.
    private val httpClient = GfnHttpClients.session

    // Identity of the create/claim/poll flow, reused by the stop request.
    @Volatile
    private var flowClientId: String? = null

    // Session id becomes available as soon as create succeeds, i.e. before the
    // queue/claim finishes, so a user cancel can still release the allocation.
    @Volatile
    private var allocatedSessionId: String? = null

    fun currentSessionId(): String? = allocatedSessionId

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

    /** Reads a JSON scalar as text, treating null/blank/non-scalar values as absent. */
    private fun jsonStringOrNull(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        return runCatching { element.jsonPrimitive.content }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.stringOrNull(key: String): String? = jsonStringOrNull(this[key])

    /** Parses the `session` object of a CloudMatch response without strict decoding. */
    private fun parseSessionJsonLenient(body: String): JsonObject? = runCatching {
        json.parseToJsonElement(body).jsonObject["session"]?.jsonObject
    }.getOrNull()

    /**
     * Resolves the streaming host directly from raw session JSON. The strict
     * SessionData decode can reject real responses (unknown/mistyped fields), so
     * claim/prefetch host re-resolution must not depend on it. Same order as
     * claimHost(): first usage=14 entry's ip -> its resourcePath host ->
     * sessionControlInfo.ip.
     */
    private fun resolveHostLenient(sessionJson: JsonObject): String? {
        val connectionInfo = sessionJson["connectionInfo"]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            .orEmpty()
        val signaling = connectionInfo
            .asSequence()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .firstOrNull {
                it.stringOrNull("usage")?.toIntOrNull() == SIGNALING_USAGE_TYPE
            }
        val signalingIp = signaling?.stringOrNull("ip")
        val resourcePath = signaling?.stringOrNull("resourcePath")
        val resourceHost = extractHostFromUrl(resourcePath)
        val controlIp = sessionJson["sessionControlInfo"]
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.stringOrNull("ip")

        val chosen = usableSessionHost(signalingIp)
            ?: resourceHost
            ?: usableSessionHost(controlIp)
        Log.i(
            TAG,
            "Lenient host resolution: signalingIp=$signalingIp, resourcePath=$resourcePath " +
                "(host=$resourceHost), sessionControlIp=$controlIp -> $chosen"
        )
        return chosen
    }

    /**
     * Reads session.iceServerConfiguration.iceServers from raw claim JSON. Each
     * entry's `urls` may be an array or a single string; username/credential are
     * optional (TURN). Malformed entries are skipped instead of failing the flow.
     */
    private fun resolveIceServersLenient(sessionJson: JsonObject): List<IceServerConfig> {
        val rawServers = sessionJson["iceServerConfiguration"]
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.get("iceServers")
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            .orEmpty()
        val configs = rawServers.mapNotNull { element ->
            val entry = runCatching { element.jsonObject }.getOrNull()
                ?: return@mapNotNull null
            val urls = when (val urlsElement = entry["urls"]) {
                is JsonArray -> urlsElement.mapNotNull { jsonStringOrNull(it) }
                is JsonPrimitive -> listOfNotNull(jsonStringOrNull(urlsElement))
                else -> emptyList()
            }
            if (urls.isEmpty()) return@mapNotNull null
            IceServerConfig(
                urls = urls,
                username = entry.stringOrNull("username"),
                credential = entry.stringOrNull("credential")
            )
        }
        Log.i(TAG, "Claim iceServers: ${configs.size} configured")
        return configs
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
        // Host actually used for the PUT (possibly re-resolved by the prefetch).
        val host: String,
        val url: String,
        val httpCode: Int?,
        val error: String? = null,
        val responseBody: String = "",
        // Host used for the prefetch GET, before re-resolution.
        val prefetchHost: String = host,
        val prefetchHttpCode: Int? = null,
        val prefetchError: String? = null,
        // Host from the 2xx claim response's own connectionInfo (lenient parse).
        val latestHost: String? = null,
        // ICE servers from the same 2xx claim response.
        val iceServers: List<IceServerConfig> = emptyList()
    )

    /**
     * Claims the session once CloudMatch reports it ready (status 2/3 and a
     * usage=14 signaling ip). Before the PUT, NanaPlay first prefetches the
     * session via GET against the assigned host and re-resolves the claim host
     * from that response; doing the PUT against the stale host sent signaling
     * to the wrong server (404 on /nvst/sign_in).
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
        var claimHost = host
        var prefetchHttpCode: Int? = null
        var prefetchError: String? = null
        var latestHost: String? = null
        var iceServers: List<IceServerConfig> = emptyList()

        // Prefetch: same CloudMatch identity headers as polling. A failure here is
        // never fatal; the PUT still falls back to the original host.
        val prefetchUrl = "https://$host/v2/session/$sessionId"
        Log.i(TAG, "Claim prefetch GET $prefetchUrl")
        try {
            val prefetchRequest = cloudmatchRequest(prefetchUrl, authToken, clientId)
                .get()
                .build()
            httpClient.newCall(prefetchRequest).execute().use { prefetchResponse ->
                prefetchHttpCode = prefetchResponse.code
                val prefetchBody = prefetchResponse.body?.string() ?: ""
                Log.i(TAG, "Claim prefetch: host=$host, HTTP ${prefetchResponse.code}")
                Log.d(TAG, "Claim prefetch body: ${prefetchBody.take(2000)}")
                if (prefetchResponse.isSuccessful) {
                    // Re-resolve from raw JSON; the strict SessionResponse decode can
                    // fail on real responses and silently leave the stale host here.
                    val resolvedHost = parseSessionJsonLenient(prefetchBody)
                        ?.let { resolveHostLenient(it) }
                    if (resolvedHost != null) {
                        claimHost = resolvedHost
                        Log.i(TAG, "Claim prefetch re-resolved host: $host -> $claimHost")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefetchError = e.localizedMessage
            Log.w(TAG, "Claim prefetch failed; keeping host $host", e)
        }

        val url = "https://$claimHost/v2/session/$sessionId?keyboardLayout=$KEYBOARD_LAYOUT&languageCode=$LANGUAGE_CODE"
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
            val outcome = httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    CrashDiagnostics.setStage("CLAIM_DONE sessionId=$sessionId")
                    Log.i(TAG, "Session claim accepted: HTTP ${response.code}")
                    // The 2xx claim body carries the authoritative connectionInfo;
                    // the poll-derived session host can still point at the old server.
                    val claimSessionJson = parseSessionJsonLenient(responseBody)
                    latestHost = claimSessionJson?.let { resolveHostLenient(it) }
                    iceServers = claimSessionJson
                        ?.let { resolveIceServersLenient(it) }
                        .orEmpty()
                    Log.i(TAG, "Claim response latest host: $latestHost")
                } else {
                    Log.w(TAG, "Session claim failed: HTTP ${response.code}: $responseBody")
                }
                Log.d(TAG, "Claim response body: $responseBody")
                ClaimOutcome(
                    host = claimHost,
                    url = url,
                    httpCode = response.code,
                    responseBody = responseBody,
                    prefetchHost = host,
                    prefetchHttpCode = prefetchHttpCode,
                    prefetchError = prefetchError,
                    latestHost = latestHost,
                    iceServers = iceServers
                )
            }

            if (outcome.httpCode?.let { it in 200..299 } == true) {
                // NanaPlay reads ICE servers from the post-claim GET poll; the PUT
                // response itself does not carry iceServerConfiguration.
                val polledIceServers = fetchClaimIceServers(
                    claimHost,
                    sessionId,
                    authToken,
                    clientId
                )
                val mergedIceServers = mergeIceServers(outcome.iceServers, polledIceServers)
                Log.i(
                    TAG,
                    "Claim ICE servers: PUT=${outcome.iceServers.size}, " +
                        "GET=${polledIceServers.size}, total=${mergedIceServers.size}"
                )
                outcome.copy(iceServers = mergedIceServers)
            } else {
                outcome
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Session claim request error", e)
            ClaimOutcome(
                host = claimHost,
                url = url,
                httpCode = null,
                error = e.localizedMessage,
                prefetchHost = host,
                prefetchHttpCode = prefetchHttpCode,
                prefetchError = prefetchError,
                latestHost = latestHost,
                iceServers = iceServers
            )
        }
    }

    /**
     * The claim PUT response does not include iceServerConfiguration. Poll the same
     * session URL up to 3 times (1s apart) and stop as soon as a non-empty list of
     * ICE servers is present, matching the native client's post-claim GET.
     */
    private fun fetchClaimIceServers(
        host: String,
        sessionId: String,
        authToken: String,
        clientId: String
    ): List<IceServerConfig> {
        val pollUrl = "https://$host/v2/session/$sessionId"
        for (attempt in 1..3) {
            try {
                if (attempt > 1) Thread.sleep(1000L)
                val request = cloudmatchRequest(pollUrl, authToken, clientId)
                    .get()
                    .build()
                val servers = httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    Log.i(
                        TAG,
                        "Post-claim ICE poll $attempt/3: host=$host, HTTP ${response.code}"
                    )
                    if (response.isSuccessful) {
                        parseSessionJsonLenient(responseBody)
                            ?.let { resolveIceServersLenient(it) }
                            .orEmpty()
                    } else {
                        emptyList()
                    }
                }
                if (servers.isNotEmpty()) return servers
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Post-claim ICE poll $attempt/3 failed", e)
            }
        }
        return emptyList()
    }

    /** Merges ICE server lists in order, keeping the first occurrence of each URL. */
    private fun mergeIceServers(
        first: List<IceServerConfig>,
        second: List<IceServerConfig>
    ): List<IceServerConfig> {
        val seenUrls = linkedSetOf<String>()
        val merged = mutableListOf<IceServerConfig>()
        (first + second).forEach { config ->
            val uniqueUrls = config.urls.filter { seenUrls.add(it) }
            if (uniqueUrls.isNotEmpty()) {
                merged.add(config.copy(urls = uniqueUrls))
            }
        }
        return merged
    }

    /** Claim details shown in the signaling-failure banner (body capped at 1500). */
    private fun buildClaimDiagnostics(claim: ClaimOutcome?): String {
        if (claim == null) return ""
        val prefetchHttp = claim.prefetchHttpCode?.toString()
            ?: claim.prefetchError
            ?: "n/a"
        val putHttp = claim.httpCode?.toString()
            ?: claim.error
            ?: "n/a"
        val claimJson = claim.responseBody
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val requestStatus = claimJson?.get("requestStatus")
            ?.let { runCatching { it.jsonObject }.getOrNull() }
        val claimSession = claimJson?.get("session")
            ?.let { runCatching { it.jsonObject }.getOrNull() }
        val statusCode = requestStatus?.stringOrNull("statusCode") ?: "n/a"
        val statusDescription = requestStatus?.stringOrNull("statusDescription") ?: "n/a"
        val sessionStatus = claimSession?.stringOrNull("status") ?: "n/a"
        return buildString {
            append("Claim prefetch: host=${claim.prefetchHost}, HTTP=$prefetchHttp\n")
            append("Claim PUT: host=${claim.host}, HTTP=$putHttp\n")
            append("Claim requestStatus.statusCode: $statusCode\n")
            append("Claim requestStatus.statusDescription: $statusDescription\n")
            append("Claim session.status: $sessionStatus")
            if (claim.responseBody.isNotBlank()) {
                append("\nClaim response (max 1500):\n")
                append(claim.responseBody.take(1500))
            }
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
            val prefetchHttp = claim.prefetchHttpCode?.toString()
                ?: claim.prefetchError
                ?: "n/a"
            append("  prefetch: host=${claim.prefetchHost}, HTTP=$prefetchHttp\n")
            append("  attempts: $claimAttempts, accepted: $accepted\n")
            append("  last host: ${claim.host}, HTTP: $http\n")
            append("  URL: PUT ${claim.url}\n")
            if (!accepted && claim.responseBody.isNotBlank()) {
                append("  Response (max 1500):\n")
                append(claim.responseBody.take(1500)).append('\n')
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
    ): Result<SignalingServerInfo> =
        try {
            createStreamingSessionInternal(authToken, appId, width, height, fps, onProgress)
        } catch (e: CancellationException) {
            // M4: the create POST and the polls block on OkHttp, so a Disconnect
            // while POST is in flight cancels this coroutine only after Cloudmatch
            // may have allocated a session. MainActivity's stopActiveSession()
            // already ran while allocatedSessionId was still null, so nobody else
            // will release this allocation: do it here before rethrowing.
            allocatedSessionId?.let { sessionId ->
                // The cleanup must run even though this coroutine is cancelled.
                withContext(NonCancellable) {
                    runCatching { stopSession(authToken, sessionId) }
                        .onFailure {
                            Log.w(TAG, "Session stop after cancellation failed for $sessionId", it)
                        }
                }
            }
            throw e
        }

    /** Body of [createStreamingSession]; cancellation cleanup lives in the caller. */
    private suspend fun createStreamingSessionInternal(
        authToken: String,
        appId: Int,
        width: Int,
        height: Int,
        fps: Int,
        onProgress: (String) -> Unit
    ): Result<SignalingServerInfo> = withContext(Dispatchers.IO) {
        // NanaPlay GfnApi.kt createSession: a fresh random nv-client-id per
        // invocation, shared by create + claim + poll within this Connect flow.
        // The old constant id was in place for a full day and matches the
        // spontaneous ~11:03 regression (suspected server-side dedup/throttle).
        val clientId = UUID.randomUUID().toString()
        flowClientId = clientId
        allocatedSessionId = null
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
            val response = httpClient.newCall(request).execute()
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
            allocatedSessionId = session.sessionId.takeIf { it.isNotBlank() }
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
            var consecutiveTransportFailures = 0
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

                // A single transport failure (Wi-Fi -> LTE handoff, DNS flap,
                // read timeout) must not kill the whole queue: count it, report
                // it, and keep polling until the 30-minute budget is exhausted.
                val pollResponse: Response
                try {
                    pollResponse = httpClient.newCall(pollReq).execute()
                    body = pollResponse.body?.string() ?: ""
                    // The blocking call above is not cancellable; observe a
                    // Disconnect that happened while it was in flight before
                    // processing/claiming this response.
                    currentCoroutineContext().ensureActive()
                } catch (e: CancellationException) {
                    // User cancelled the queue: propagate, never treat as a poll failure.
                    throw e
                } catch (e: Exception) {
                    consecutiveTransportFailures++
                    lastPollBody = "transport error: ${e.javaClass.simpleName}: ${e.localizedMessage}"
                    pollLines += "Poll $retries/$MAX_POLL_ATTEMPTS: transport error " +
                        "${e.javaClass.simpleName}: ${e.localizedMessage} " +
                        "(consecutive=$consecutiveTransportFailures), url=$pollUrl"
                    Log.w(TAG, "Poll $retries/$MAX_POLL_ATTEMPTS transport error", e)
                    val failedHost = runCatching { URI(pollUrl).host }.getOrNull() ?: pollUrl
                    onProgress(
                        "Session poll error @ $failedHost (${e.javaClass.simpleName}, " +
                            "consecutive=$consecutiveTransportFailures); retrying..."
                    )
                    continue
                }
                consecutiveTransportFailures = 0
                lastPollBody = body
                if (!pollResponse.isSuccessful) {
                    pollLines += "Poll $retries/$MAX_POLL_ATTEMPTS: HTTP ${pollResponse.code}, status=n/a, connectionInfo=no, url=$pollUrl"
                    Log.w(TAG, "Poll $retries/$MAX_POLL_ATTEMPTS failed: HTTP ${pollResponse.code}: $body")
                    val failedHost = runCatching { URI(pollUrl).host }.getOrNull() ?: pollUrl
                    if (pollResponse.code == 500) {
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
                    onProgress("Session poll failed (HTTP ${pollResponse.code}) @ $failedHost; retrying...")
                    continue
                }
                consecutivePoll500s = 0

                sessionResp = json.decodeFromString<SessionResponse>(body)
                // Merge: queued responses omit connectionInfo/sessionControlInfo, so a
                // replace would erase a server host learned from an earlier response.
                session = session.mergedWith(sessionResp.session ?: session)
                allocatedSessionId = session.sessionId.takeIf { it.isNotBlank() }
                val connectionSummary = session.connectionInfo?.let { "yes(${it.size})" } ?: "no"
                pollLines += "Poll $retries/$MAX_POLL_ATTEMPTS: HTTP ${pollResponse.code}, " +
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
                        // A cancelled queue must never claim an allocation.
                        currentCoroutineContext().ensureActive()
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
            // The 2xx claim response is authoritative: its connectionInfo can name a
            // different server than the stale poll merge (v34: .38 vs .36).
            val pollHost = usableSessionHost(signalingInfo.ip)
                ?: session.claimHost()
            val host = claimOutcome?.latestHost
                ?: pollHost
                ?: return@withContext Result.failure(Exception("No usable signaling host in connectionInfo: $connectionList"))
            Log.i(
                TAG,
                "Signaling host selection: claimResponse=${claimOutcome?.latestHost}, " +
                    "poll=$pollHost -> $host"
            )
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
                    sessionId = session.sessionId,
                    claimDiagnostics = buildClaimDiagnostics(claimOutcome),
                    iceServers = claimOutcome?.iceServers.orEmpty()
                )
            )
        } catch (e: CancellationException) {
            // Queue cancelled by the user / Activity teardown.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception during session creation", e)
            Result.failure(e)
        }
    }

    /**
     * Releases a Cloudmatch allocation with `DELETE /v2/session/{id}` using the
     * same identity/auth headers as create/claim/poll. Best effort only: a
     * non-2xx response is logged, never thrown to the caller.
     */
    suspend fun stopSession(authToken: String, sessionId: String): Result<Int> =
        withContext(Dispatchers.IO) {
            val clientId = flowClientId ?: UUID.randomUUID().toString()
            val url = "${cloudmatchUrl.trimEnd('/')}/v2/session/${sessionId.trim()}"
            try {
                val request = cloudmatchRequest(url, authToken, clientId)
                    .delete()
                    .build()
                Log.i(TAG, "Stopping Cloudmatch session $sessionId via DELETE $url")
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        Log.i(TAG, "Cloudmatch session stop accepted: HTTP ${response.code}")
                        Result.success(response.code)
                    } else {
                        Log.w(TAG, "Cloudmatch session stop failed: HTTP ${response.code}: $body")
                        Result.failure(Exception("HTTP ${response.code}: ${body.take(500)}"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cloudmatch session stop request error", e)
                Result.failure(e)
            }
        }
}
