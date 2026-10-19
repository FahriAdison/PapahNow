package com.papahchan.papahnow.auth

import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class AuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("client_token") val clientToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)

class GfnAuthManager(
    private val clientId: String = "Lgg_4meEdcNzoj7z2grMvEzUN1iB7g8VEdyYaUrvhms",
    private val redirectUri: String = "nvidia://auth_redirect",
    private val scope: String = "openid consent email tk_client age"
) {
    companion object {
        private const val TAG = "GfnAuthManager"
        private const val AUTH_ENDPOINT = "https://login.nvidia.com/authorize"
        private const val TOKEN_ENDPOINT = "https://login.nvidia.com/token"
    }

    private val httpClient = OkHttpClient()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var currentCodeVerifier: String? = null

    /**
     * Generates PKCE parameters and constructs the OAuth2 authorization URL.
     */
    fun createAuthorizationUrl(): String {
        val verifier = generateCodeVerifier()
        currentCodeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)
        val nonce = generateRandomString(16)

        val uri = Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("device_id", "gfnclient")
            .appendQueryParameter("scope", scope)
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("nonce", nonce)
            .appendQueryParameter("prompt", "select_account")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        val url = uri.toString()
        Log.i(TAG, "Generated OAuth URL: $url")
        return url
    }

    fun getActiveCodeVerifier(): String? = currentCodeVerifier

    fun setManualCodeVerifier(verifier: String) {
        currentCodeVerifier = verifier
    }

    /**
     * Exchanges the authorization code received from redirect for OAuth tokens.
     */
    suspend fun exchangeAuthorizationCode(
        code: String,
        verifier: String? = currentCodeVerifier
    ): Result<AuthTokens> = withContext(Dispatchers.IO) {
        val codeVerifier = verifier ?: run {
            return@withContext Result.failure(IllegalStateException("No code verifier found for PKCE exchange"))
        }

        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId)
                .add("code_verifier", codeVerifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            Log.i(TAG, "Exchanging authorization code with $TOKEN_ENDPOINT...")
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Token exchange failed: HTTP ${response.code}: $body")
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val tokens = json.decodeFromString<AuthTokens>(body)
            Log.i(TAG, "OAuth tokens acquired successfully! (accessToken length: ${tokens.accessToken.length})")
            Result.success(tokens)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during token exchange", e)
            Result.failure(e)
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateRandomString(length: Int): String {
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return (1..length)
            .map { allowedChars[random.nextInt(allowedChars.length)] }
            .joinToString("")
    }
}
