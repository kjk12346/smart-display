package dev.smartdisplay.app

import android.app.Application
import dev.smartdisplay.app.server.ServerStore
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Holds the app-wide singletons. */
class SmartDisplayApp : Application() {
    val serverStore by lazy { ServerStore(this) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
