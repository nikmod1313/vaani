package app.vaaniledger.net

import android.content.Context
import app.vaaniledger.BuildConfig

/**
 * Where the API lives, at runtime rather than at compile time.
 *
 * `BuildConfig.API_BASE` is baked in when the APK is built and defaults to
 * `http://10.0.2.2:8000/` -- the EMULATOR's alias for the host machine. On a
 * real phone that address resolves to nothing, so every extraction call
 * failed silently and the bubble looked broken when the actual problem was
 * one URL.
 *
 * Rebuilding with `-PapiBase=...` every time a phone changes network is not
 * a fix a shopkeeper's phone can apply. So the address is now a runtime
 * setting: it starts from the build config, can be overridden from the
 * settings screen, and survives an app restart.
 */
object ServerSettings {

    private const val PREFS = "vaani_server"
    private const val KEY_BASE_URL = "base_url"

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.API_BASE

    /** True once someone has overridden the build default. */
    fun isCustom(context: Context): Boolean =
        !prefs(context).getString(KEY_BASE_URL, null).isNullOrBlank()

    /**
     * Set a new server address.
     *
     * Normalised to always end in "/" -- Retrofit's `baseUrl` silently
     * builds every relative path wrong without it, which is a confusing way
     * to discover a typo.
     */
    fun setBaseUrl(context: Context, url: String) {
        val trimmed = url.trim()
        val normalised = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        prefs(context).edit().putString(KEY_BASE_URL, normalised).apply()
        ApiClient.invalidate()
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_BASE_URL).apply()
        ApiClient.invalidate()
    }

    /** A guess worth offering when someone opens the settings screen. */
    fun buildDefault(): String = BuildConfig.API_BASE

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
