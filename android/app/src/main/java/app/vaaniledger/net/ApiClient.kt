package app.vaaniledger.net

import android.content.Context
import android.util.Log
import app.vaaniledger.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * The Retrofit instance, rebuilt when the server address changes.
 *
 * Used to be a bare `by lazy` over a compile-time constant, which is exactly
 * why the app was unusable on a real phone: `BuildConfig.API_BASE` defaults
 * to the emulator's loopback alias, baked in at build time, and no amount of
 * retrying from the phone would ever reach it. [ServerSettings] now owns the
 * address; this class just has to notice when it changes.
 */
object ApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedApi: VaaniApi? = null

    /** The live API, pointed at whatever [ServerSettings] currently holds. */
    fun api(context: Context): VaaniApi {
        val url = ServerSettings.baseUrl(context)
        val cached = cachedApi
        if (cached != null && url == cachedUrl) return cached

        val built = build(url)
        cachedUrl = url
        cachedApi = built
        return built
    }

    /** Force the next [api] call to rebuild, e.g. after a settings change. */
    fun invalidate() {
        cachedApi = null
        cachedUrl = null
    }

    private fun build(baseUrl: String): VaaniApi {
        val logging = HttpLoggingInterceptor { Log.d("VaaniHttp", it) }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            // Short timeouts: this sits in front of a shopkeeper mid-sale.
            // Falling back to rules-only locally beats a 30-second spinner.
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(VaaniApi::class.java)
    }
}
