# Official TDLib Android artifact

Build the official TDLib Android example from https://github.com/tdlib/td/tree/master/example/android using the project Android SDK/NDK, then copy the generated Java-interface AAR/native libraries into this directory as `tdlib.aar` and the matching `jni/<abi>` folders.

Do not commit Telegram API hashes, account sessions, OTPs, passwords, or tokens. Supply the API ID and API hash through user-level `gradle.properties`:

```properties
audiophileTelegramApiId=123456
audiophileTelegramApiHash=provided-out-of-band
```
