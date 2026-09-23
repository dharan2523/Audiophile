package com.audiophile

import android.app.Application
import androidx.room.Room
import com.audiophile.data.AudioDatabase
import com.audiophile.data.AudioRepository
import com.audiophile.domain.DropboxSource
import com.audiophile.domain.EmbySource
import com.audiophile.domain.GoogleDriveSource
import com.audiophile.domain.LocalMusicSource
import com.audiophile.domain.SourceRegistry
import com.audiophile.domain.UpnpSource
import com.audiophile.domain.WebDavSource
import com.audiophile.domain.TelegramSource
import com.audiophile.domain.TelegramMediaCache
import com.audiophile.domain.TdLibGateway
import com.audiophile.security.SecureTokenStore
import com.audiophile.security.OAuthClient
import okhttp3.OkHttpClient
import com.audiophile.security.GoogleDriveAuth
import java.util.concurrent.TimeUnit

class AudiophileApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, AudioDatabase::class.java, "audiophile.db").fallbackToDestructiveMigration().build() }
    val repository by lazy { AudioRepository(this, database.audioDao()) }
    val secureTokenStore by lazy { SecureTokenStore(this) }
    val httpClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    val oauthClient by lazy { OAuthClient(httpClient, secureTokenStore) }
    val googleDriveAuth by lazy { GoogleDriveAuth(this, secureTokenStore) }
    val telegramMediaCache by lazy { TelegramMediaCache(this) }
    val telegramSource by lazy { TelegramSource(TdLibGateway(this, BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH, secureTokenStore), secureTokenStore, telegramMediaCache) }
    val sourceRegistry by lazy {
        val settings = getSharedPreferences("source_settings", MODE_PRIVATE)
        SourceRegistry(
            LocalMusicSource(repository),
            listOf(
                GoogleDriveSource(httpClient, secureTokenStore) { googleDriveAuth.refreshAccessToken() },
                DropboxSource(httpClient, secureTokenStore) { oauthClient.refresh(com.audiophile.security.OAuthProviders.dropbox, BuildConfig.DROPBOX_CLIENT_ID, com.audiophile.security.OAuthProviders.redirectUri).isSuccess },
                WebDavSource(httpClient, secureTokenStore, settings.getString("webdav_url", "").orEmpty(), settings.getString("webdav_username", "").orEmpty()),
                UpnpSource(httpClient, secureTokenStore, settings.getString("upnp_content_directory_url", "").orEmpty()),
                EmbySource(httpClient, secureTokenStore, settings.getString("emby_server_url", "").orEmpty()),
                telegramSource
            )
        )
    }
}
