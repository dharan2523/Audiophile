import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use { load(it) }
}
val dropboxClientId = providers.gradleProperty("audiophileDropboxClientId").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: localProperties.getProperty("audiophileDropboxClientId")?.trim().orEmpty()
val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull

android {
    namespace = "com.audiophile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.audiophile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    val telegramApiId = providers.gradleProperty("audiophileTelegramApiId").orNull ?: "0"
    val telegramApiHash = providers.gradleProperty("audiophileTelegramApiHash").orNull ?: ""
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (releaseKeystorePath != null && releaseKeystorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
            create("ciRelease") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "DROPBOX_CLIENT_ID", "\"$dropboxClientId\"")
            buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
            buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        }
        getByName("release") {
            signingConfigs.findByName("ciRelease")?.let { signingConfig = it }
            buildConfigField("String", "DROPBOX_CLIENT_ID", "\"$dropboxClientId\"")
            buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
            buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        }
    }
    sourceSets["main"].jniLibs.srcDirs("libs/jni")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    val tdlibAar = file("libs/tdlib.aar")
    if (tdlibAar.exists()) implementation(files(tdlibAar))
}
