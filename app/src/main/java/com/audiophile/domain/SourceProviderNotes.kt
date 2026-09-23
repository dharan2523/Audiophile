package com.audiophile.domain

/** Provider adapters should implement MusicSource and keep credentials outside the library database. */
object SupportedSourceIds {
    const val GOOGLE_DRIVE = "drive"
    const val DROPBOX = "dropbox"
    const val WEBDAV = "webdav"
    const val UPNP = "upnp"
    const val EMBY = "emby"
}
