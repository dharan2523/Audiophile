package com.audiophile.security

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleDriveAuth(
    context: Context,
    private val tokenStore: SecureTokenStore
) {
    private val appContext = context.applicationContext

    fun signInIntent(): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_READONLY_SCOPE))
            .build()
        return GoogleSignIn.getClient(appContext, options).signInIntent
    }

    suspend fun completeSignIn(data: Intent?): Result<Unit> = runCatching {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        requireNotNull(account.account) { "Google account is unavailable" }
        storeAccessToken(account)
    }

    suspend fun refreshAccessToken(): Boolean = runCatching {
        val account = GoogleSignIn.getLastSignedInAccount(appContext)
            ?: error("Google account is unavailable")
        storeAccessToken(account)
    }.isSuccess

    fun disconnect() {
        GoogleSignIn.getClient(
            appContext,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        ).signOut()
        tokenStore.remove(ACCESS_TOKEN_KEY)
        tokenStore.remove(ACCOUNT_NAME_KEY)
    }

    private suspend fun storeAccessToken(account: GoogleSignInAccount) {
        val googleAccount = requireNotNull(account.account) { "Google account is unavailable" }
        val token = withContext(Dispatchers.IO) {
            GoogleAuthUtil.getToken(appContext, googleAccount, "oauth2:$DRIVE_READONLY_SCOPE")
        }
        require(token.isNotBlank()) { "Google did not return an access token" }
        tokenStore.put(ACCESS_TOKEN_KEY, token)
        tokenStore.put(ACCOUNT_NAME_KEY, googleAccount.name ?: error("Google account name is unavailable"))
    }

    companion object {
        const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val ACCESS_TOKEN_KEY = "google_drive_access_token"
        private const val ACCOUNT_NAME_KEY = "google_drive_account_name"
    }
}
