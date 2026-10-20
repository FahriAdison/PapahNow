package com.papahchan.papahnow.session

import android.util.Log
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
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class SessionResponse(
    @SerialName("session") val session: SessionData? = null,
    @SerialName("requestStatus") val requestStatus: JsonObject? = null
)

@Serializable
data class SessionData(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("state") val state: String? = null,
    @SerialName("connectionInfo") val connectionInfo: List<ConnectionInfoItem>? = null
)

@Serializable
data class ConnectionInfoItem(
    @SerialName("usage") val usage: Int, // 14 = WebSocket Signaling, 2/17 = Media
    // Non-signaling entries can carry a null ip (classic routing), so decode must not
    // fail here; the signaling lookup validates it instead.
    @SerialName("ip") val ip: String? = null,
    @SerialName("port") val port: Int,
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

        // Asia-Pacific regional host (ap-india), from browser capture. Use
        // "https://prod.cloudmatchbeta.nvidiagrid.net" for global routing.
        private const val DEFAULT_CLOUDMATCH_URL = "https://ap-india.cloudmatchbeta.nvidiagrid.net"

        // Query parameters required by the session request endpoint.
        private const val KEYBOARD_LAYOUT = "en_US"
        private const val LANGUAGE_CODE = "en_US"

        // GFN expects its own JWT scheme for the Authorization header, not Bearer.
        private const val AUTH_SCHEME = "GFNJWT"

        // Body identity, matching the proven NanaPlay client. clientVersion stays "30.0"
        // (Cloudmatch client identity); the Android app version goes in nv-client-version.
        private const val CLIENT_ID = "ec7e38d4-03af-4b58-b131-cfb0495903ab"
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
    private fun cloudmatchRequest(url: String, authToken: String): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "$AUTH_SCHEME $authToken")
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Origin", ORIGIN)
            .addHeader("Referer", REFERER)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-device-id", deviceHashId)
            .addHeader("nv-client-id", CLIENT_ID)
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

    /**
     * GFN expects the timezone offset in milliseconds (positive east of UTC), e.g.
     * +25200000 for UTC+7. Computed per request so timezone changes are picked up.
     */
    private fun currentTimezoneOffsetMs(): Int =
        TimeZone.getDefault().getOffset(System.currentTimeMillis())

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
        fps: Int = 60
    ): Result<SignalingServerInfo> = withContext(Dispatchers.IO) {
        val baseEndpoint = "${cloudmatchUrl.trimEnd('/')}/v2/session"
        // Query params required by the session endpoint, per browser capture.
        val endpoint = "$baseEndpoint?keyboardLayout=$KEYBOARD_LAYOUT&languageCode=$LANGUAGE_CODE"
        val subSessionId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("sessionRequestData", buildJsonObject {
                put("appId", appId)
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
                put("enablePersistingInGameSettings", false)
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
                })
                put("clientRequestMonitorSettings", buildJsonArray {
                    add(buildJsonObject {
                        put("monitorId", 0)
                        put("positionX", 0)
                        put("positionY", 0)
                        put("widthInPixels", width)
                        put("heightInPixels", height)
                        put("dpi", 0)
                        put("framesPerSecond", fps)
                        put("sdrHdrMode", 0)
                    })
                })
                put("appLaunchMode", 1)
                put("sdkVersion", "1.0")
                put("enhancedStreamMode", 1)
                put("useOps", true)
                put("requestedStreamingFeatures", buildJsonObject {
                    put("reflex", false)
                    put("bitDepth", 0)
                    put("cloudGsync", false)
                    put("enabledL4S", false)
                    put("profile", 0)
                    put("fallbackToLogicalResolution", false)
                    put("chromaFormat", 0)
                    put("prefilterMode", 0)
                    put("hudStreamingMode", 0)
                })
            })
        }.toString()

        Log.i(TAG, "Requesting session from $endpoint...")
        val requestBody = payload.toRequestBody("application/json".toMediaType())
        val request = cloudmatchRequest(endpoint, authToken)
            .post(requestBody)
            .build()

        // Full request dump for diffing against browser captures. The JWT is censored.
        Log.d(TAG, "POST $endpoint")
        request.headers.forEach { (name, value) ->
            val logged = if (name.equals("Authorization", ignoreCase = true)) maskAuthorization(value) else value
            Log.d(TAG, "  $name: $logged")
        }
        Log.d(TAG, "Request body: $payload")

        try {
            var response = httpClient.newCall(request).execute()
            var body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Session creation failed: HTTP ${response.code}: $body")
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            var sessionResp = json.decodeFromString<SessionResponse>(body)
            var session = sessionResp.session ?: return@withContext Result.failure(Exception("No session object in response: $body"))

            // If session is queued or not ready yet, poll for a short period
            var retries = 0
            while (session.state != "ready_for_connection" && session.connectionInfo.isNullOrEmpty() && retries < 15) {
                delay(2000)
                retries++
                Log.i(TAG, "Polling session status (${session.sessionId}), state=${session.state}, attempt $retries...")

                // Polling uses the same identity headers; path without query params.
                val pollReq = cloudmatchRequest("$baseEndpoint/${session.sessionId}", authToken)
                    .get()
                    .build()

                response = httpClient.newCall(pollReq).execute()
                body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    sessionResp = json.decodeFromString<SessionResponse>(body)
                    session = sessionResp.session ?: session
                }
            }

            // Raw connectionInfo (including fields we don't model) so classic-routing
            // responses can be inspected directly in Logcat.
            val rawConnectionInfo = runCatching {
                json.parseToJsonElement(body).jsonObject["session"]?.jsonObject?.get("connectionInfo")
            }.getOrNull()
            Log.i(TAG, "Raw connectionInfo: $rawConnectionInfo")

            val connectionList = session.connectionInfo
                ?: return@withContext Result.failure(Exception("Session created but no connectionInfo received. State: ${session.state}"))

            Log.i(TAG, "connectionInfo: $connectionList")

            val signalingInfo = connectionList.find { it.usage == SIGNALING_USAGE_TYPE }
                ?: return@withContext Result.failure(Exception("No signaling server (usage=$SIGNALING_USAGE_TYPE) found in connectionInfo: $connectionList"))

            val host = signalingInfo.ip
                ?: return@withContext Result.failure(Exception("Signaling server (usage=$SIGNALING_USAGE_TYPE) has no ip: $signalingInfo"))
            val secure = signalingInfo.appLevelProtocol == 5 // 5 = wss
            Log.i(TAG, "Signaling server found: host=$host, port=${signalingInfo.port}, secure=$secure, resourcePath=${signalingInfo.resourcePath}")

            Result.success(
                SignalingServerInfo(
                    host = host,
                    port = signalingInfo.port,
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
