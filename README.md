# Audiophile

A clean, dark-first Android music player built from scratch with Kotlin, Jetpack Compose, Material 3, Media3, Room, Coroutines, and Coil.

## Architecture

- `data`: Room cache and MediaStore scanner, all exposed as Flow.
- `playback`: one ExoPlayer owned by `MediaSessionService`; app and widget use the same session.
- `domain`: source and lyrics contracts that can be implemented by local, Drive, Dropbox, WebDAV, UPnP/DLNA, and Emby adapters.
- Compose UI: small state-driven screens with lazy lists and no blocking work on the main thread.

Open the project in Android Studio and run the `app` configuration. On Android 13+, grant audio access from the Home screen to scan local music.

## Provider configuration

Put the non-secret Dropbox OAuth client ID in `local.properties` (or the user-level `gradle.properties` file). Never commit client secrets:

```properties
audiophileDropboxClientId=your-dropbox-client-id
audiophileTelegramApiId=your-telegram-api-id
audiophileTelegramApiHash=your-telegram-api-hash
```

Google Drive uses the Android Google Sign-In authorization flow. It does not require a client ID or client secret in the app setup screen, and it does not use the Dropbox callback URI. In Google Cloud Console, enable the Google Drive API, configure the OAuth consent screen, add a test user while the app is in testing, and create an Android OAuth client for package `com.audiophile`. Register the SHA-1 fingerprint for every signing certificate used to install the app: the debug certificate for debug APKs and the release certificate for release APKs. The app requests the Drive read-only scope and stores the resulting credentials through Android Keystore-backed storage.

The `com.audiophile://oauth/callback` intent filter is retained for Dropbox's PKCE web flow only. WebDAV, UPnP, and Emby server settings are stored in the app's source settings.

Telegram uses the official TDLib Android artifact. Follow `app/libs/README.md` to build and add `tdlib.aar`; API ID/hash are external configuration values and never logged.
