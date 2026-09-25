package dev.smartdisplay.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.smartdisplay.app.auth.AuthStore
import dev.smartdisplay.app.auth.HomeAssistantAuth
import dev.smartdisplay.app.auth.Session
import dev.smartdisplay.app.ha.HomeAssistantClient
import dev.smartdisplay.app.kiosk.KioskStore
import dev.smartdisplay.app.server.ServerStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Holds the app-wide singletons. */
class SmartDisplayApp : Application(), SingletonImageLoader.Factory {
    /** For work that must outlive any one screen, such as finishing a sign-in. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val serverStore by lazy { ServerStore(this) }

    val kiosk by lazy { KioskStore(this) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val session by lazy { Session(HomeAssistantAuth(http), AuthStore(this), serverStore, appScope) }

    /** The live connection; it connects only while a screen is collecting its state. */
    val home by lazy { HomeAssistantClient(http, session, { serverStore.server.value?.url }, appScope) }

    /** Coil, for wallpaper photos, over the same HTTP client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .build()
}
