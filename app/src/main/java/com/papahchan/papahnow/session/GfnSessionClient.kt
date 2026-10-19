package com.papahchan.papahnow.session

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    @SerialName("ip") val ip: String,
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
    private val cloudmatchUrl: String = "https://prod.cloudmatchbeta.nvidiagrid.net"
) {
    companion object {
        private const val TAG = "GfnSessionClient"
        private const val SIGNALING_USAGE_TYPE = 14
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
     * Requests a game streaming session from Cloudmatch API.
     */
    suspend fun createStreamingSession(
        accessToken: String,
        appId: Int = 0, // 0 for default / initial session test
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 60
    ): Result<SignalingServerInfo> = withContext(Dispatchers.IO) {
        val endpoint = "${cloudmatchUrl.trimEnd('/')}/v2/session"
        val payload = buildJsonObject {
            put("appId", appId)
            put("audioMode", 2)
            put("remoteControllersBitmap", 0)
            put("sdrHdrMode", 0)
            put("clientPlatformName", "android")
            put("clientIdentification", "GFN-PC")
            put("metaData", buildJsonArray {
                add(buildJsonObject {
                    put("key", "wssignaling")
                    put("value", "1")
                })
            })
            put("clientRequestMonitorSettings", buildJsonArray {
                add(buildJsonObject {
                    put("width", width)
                    put("height", height)
                    put("fps", fps)
                    put("sdrHdrMode", 0)
                })
            })
            put("appLaunchMode", 1)
            put("sdkVersion", "1.0")
            put("enhancedStreamMode", 1)
            put("useOps", true)
        }.toString()

        Log.i(TAG, "Requesting session from $endpoint...")
        val requestBody = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-device-id", "gfnclient")
            .addHeader("nv-client-type", "mobile")
            .addHeader("nv-browser-type", "Chrome")
            .addHeader("nv-device-os", "Android")
            .build()

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

                val pollReq = Request.Builder()
                    .url("$endpoint/${session.sessionId}")
                    .get()
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-device-id", "gfnclient")
                    .build()

                response = httpClient.newCall(pollReq).execute()
                body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    sessionResp = json.decodeFromString<SessionResponse>(body)
                    session = sessionResp.session ?: session
                }
            }

            val connectionList = session.connectionInfo
                ?: return@withContext Result.failure(Exception("Session created but no connectionInfo received. State: ${session.state}"))

            val signalingInfo = connectionList.find { it.usage == SIGNALING_USAGE_TYPE }
                ?: return@withContext Result.failure(Exception("No signaling server (usage=$SIGNALING_USAGE_TYPE) found in connectionInfo: $connectionList"))

            val secure = signalingInfo.appLevelProtocol == 5 // 5 = wss
            Log.i(TAG, "Signaling server found: host=${signalingInfo.ip}, port=${signalingInfo.port}, secure=$secure, resourcePath=${signalingInfo.resourcePath}")

            Result.success(
                SignalingServerInfo(
                    host = signalingInfo.ip,
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
