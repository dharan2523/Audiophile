package com.audiophile.security

import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class OAuthProvider(
    val id: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: String
)

data class OAuthTokenSet(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long?)

class OAuthClient(private val client: OkHttpClient, private val tokenStore: SecureTokenStore) {
    fun saveClientConfiguration(provider: OAuthProvider, clientId: String, clientSecret: String) {
        tokenStore.put("oauth_${provider.id}_client_id", clientId)
        tokenStore.put("oauth_${provider.id}_client_secret", clientSecret)
    }

    fun clientId(provider: OAuthProvider): String? = tokenStore.get("oauth_${provider.id}_client_id")
    fun clientSecret(provider: OAuthProvider): String? = tokenStore.get("oauth_${provider.id}_client_secret")

    fun clear(provider: OAuthProvider) {
        listOf(
            "oauth_${provider.id}_client_id",
            "oauth_${provider.id}_client_secret",
            "${provider.id}_access_token",
            "${provider.id}_refresh_token",
            "oauth_${provider.id}_state",
            "oauth_${provider.id}_verifier"
        ).forEach(tokenStore::remove)
    }

    fun authorizationUrl(provider: OAuthProvider, clientId: String, redirectUri: String, state: String, codeChallenge: String): Uri = Uri.parse(provider.authorizationEndpoint).buildUpon()
        .appendQueryParameter("client_id", clientId)
        .appendQueryParameter("redirect_uri", redirectUri)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("scope", provider.scopes)
        .appendQueryParameter("state", state)
        .appendQueryParameter("code_challenge", codeChallenge)
        .appendQueryParameter("code_challenge_method", "S256")
        .apply { if (provider.id == "dropbox") appendQueryParameter("token_access_type", "offline") else appendQueryParameter("access_type", "offline") }
        .build()

    fun begin(provider: OAuthProvider, clientId: String, redirectUri: String): Uri {
        val state = randomUrlToken(24)
        val verifier = randomUrlToken(48)
        tokenStore.put("oauth_${provider.id}_state", state)
        tokenStore.put("oauth_${provider.id}_verifier", verifier)
        return authorizationUrl(provider, clientId, redirectUri, state, challenge(verifier))
    }

    suspend fun exchangeCode(provider: OAuthProvider, clientId: String, redirectUri: String, code: String, clientSecret: String? = null): Result<OAuthTokenSet> = runCatching {
        val verifier = tokenStore.get("oauth_${provider.id}_verifier") ?: error("OAuth session expired")
        val bodyBuilder = FormBody.Builder()
            .add("client_id", clientId)
            .add("redirect_uri", redirectUri)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("grant_type", "authorization_code")
        clientSecret?.takeIf { it.isNotBlank() }?.let { bodyBuilder.add("client_secret", it) }
        val body = bodyBuilder.build()
        val request = Request.Builder().url(provider.tokenEndpoint).post(body).build()
        val payload = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("OAuth token exchange failed: ${response.code}")
                response.body?.string().orEmpty()
            }
        }
        val json = JSONObject(payload)
        val tokens = OAuthTokenSet(json.getString("access_token"), json.optString("refresh_token").ifBlank { null }, json.optLong("expires_in").takeIf { it > 0 })
        tokenStore.put("${provider.id}_access_token", tokens.accessToken)
        tokens.refreshToken?.let { tokenStore.put("${provider.id}_refresh_token", it) }
        tokenStore.remove("oauth_${provider.id}_state")
        tokenStore.remove("oauth_${provider.id}_verifier")
        tokens
    }

    suspend fun refresh(provider: OAuthProvider, clientId: String, redirectUri: String, clientSecret: String? = null): Result<OAuthTokenSet> = runCatching {
        val refreshToken = tokenStore.get("${provider.id}_refresh_token") ?: error("Authentication required")
        val bodyBuilder = FormBody.Builder().add("client_id", clientId).add("refresh_token", refreshToken).add("grant_type", "refresh_token").add("redirect_uri", redirectUri)
        clientSecret?.takeIf { it.isNotBlank() }?.let { bodyBuilder.add("client_secret", it) }
        val body = bodyBuilder.build()
        val request = Request.Builder().url(provider.tokenEndpoint).post(body).build()
        val payload = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Refresh token rejected")
                response.body?.string().orEmpty()
            }
        }
        val json = JSONObject(payload)
        val tokens = OAuthTokenSet(json.getString("access_token"), json.optString("refresh_token").ifBlank { refreshToken }, json.optLong("expires_in").takeIf { it > 0 })
        tokenStore.put("${provider.id}_access_token", tokens.accessToken)
        tokens.refreshToken?.let { tokenStore.put("${provider.id}_refresh_token", it) }
        tokens
    }

    fun expectedState(provider: OAuthProvider): String? = tokenStore.get("oauth_${provider.id}_state")

    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
    private fun challenge(verifier: String): String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
