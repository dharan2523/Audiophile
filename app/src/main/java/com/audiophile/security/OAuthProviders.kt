package com.audiophile.security

object OAuthProviders {
    const val redirectUri = "com.audiophile://oauth/callback"
    val dropbox = OAuthProvider(
        id = "dropbox",
        authorizationEndpoint = "https://www.dropbox.com/oauth2/authorize",
        tokenEndpoint = "https://api.dropboxapi.com/oauth2/token",
        scopes = "files.content.read files.metadata.read"
    )
}
