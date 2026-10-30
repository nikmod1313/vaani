package app.vaaniledger.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The online dashboard: this phone's own secret key, and the code it shares.
 *
 * The KEY never leaves the phone except to the server it syncs with, which
 * keeps only a hash. The first key a shop's server sees is the one it trusts,
 * so nobody else can write into this khata by knowing the shop's id.
 *
 * The CODE is what the shopkeeper hands out: typed on the website it opens
 * this shop's khata, and it keeps working until a new one is made here --
 * which signs every browser holding the old one out. The server is sent its
 * hash on every sync, so a server that lost its disk learns it again.
 */
object DashboardAccess {

    private const val PREFS = "vaani_dashboard"
    private const val KEY_DEVICE = "device_key"
    private const val KEY_CODE = "code"

    /** No 0/O, 1/I/L: read aloud or copied by hand, these get mixed up. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun deviceKey(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_DEVICE, key).apply()
        return key
    }

    fun code(context: Context): String? = prefs(context).getString(KEY_CODE, null)?.takeIf { it.isNotBlank() }

    fun setCode(context: Context, code: String?) {
        prefs(context).edit().putString(KEY_CODE, code).apply()
    }

    /** "VK7Q-M2PD": 8 characters from 31, about 40 bits -- not guessable at 10 tries per 15 minutes. */
    fun newCode(): String {
        val chars = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return chars.take(4) + "-" + chars.drop(4)
    }

    /** "vk7q m2pd" and "VK7Q-M2PD" are one code. Mirrors `normalise_code` on the server. */
    fun normalise(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    /** What the server stores and matches. Mirrors `code_hash` in main.py. */
    fun hash(code: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("vaani-dashboard-code:" + normalise(code)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
