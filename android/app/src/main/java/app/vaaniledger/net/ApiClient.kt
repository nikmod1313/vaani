package app.vaaniledger.net

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
 * The one Retrofit instance.
 *
 * Built lazily so a cold start never pays for it, and so a bad base URL
 * fails at the first call with a clear message rather than at process start
 * with a stack trace nobody reads.
 */
object ApiClient {

    /**
     * Lenient on purpose. The server may add fields before the app knows
     * about them, and an unknown key must not crash an entry the shopkeeper
     * is halfway through confirming.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    val api: VaaniApi by lazy {
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

        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(VaaniApi::class.java)
    }
}
