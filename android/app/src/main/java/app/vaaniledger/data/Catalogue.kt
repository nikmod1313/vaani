package app.vaaniledger.data

import android.content.Context
import app.vaaniledger.extract.CategoryLexicon
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One thing the shop sells, at the price the shop charges. */
@Serializable
data class CatalogueItem(
    val name: String,
    val price: Double? = null,
    /** "piece", "kg", "packet" -- what the price is per. Free text. */
    val unit: String? = null,
)

/**
 * The shop's own price list.
 *
 * The built-in vocabulary knows "kurti" but not "muslin kurti", and knows
 * no shop's prices at all. A shopkeeper who writes their list once gets
 * both: every name on it is read as an item -- however unusual -- and an
 * order that names items but no total is priced from it.
 *
 * Kept in preferences, not Room: it is one small list read whole, and a
 * schema migration for it would be all cost.
 */
object Catalogue {

    private const val PREFS = "vaani_catalogue"
    private const val KEY_ITEMS = "items"
    private const val KEY_UPDATED = "updated_at"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CatalogueItem.serializer())

    fun items(context: Context): List<CatalogueItem> =
        prefs(context).getString(KEY_ITEMS, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyList()

    /** Millis; last writer wins between the phone and the dashboard. */
    fun updatedAt(context: Context): Long = prefs(context).getLong(KEY_UPDATED, 0L)

    fun save(context: Context, items: List<CatalogueItem>, updatedAt: Long = System.currentTimeMillis()) {
        val clean = items.filter { it.name.isNotBlank() }.distinctBy { it.name.trim().lowercase() }
        prefs(context).edit()
            .putString(KEY_ITEMS, json.encodeToString(serializer, clean))
            .putLong(KEY_UPDATED, updatedAt)
            .apply()
        install(clean)
    }

    /** Teach the engine this shop's words. Called at start-up and on every save. */
    fun install(context: Context) = install(items(context))

    fun install(items: List<CatalogueItem>) =
        CategoryLexicon.useShopVocabulary(items.map { it.name })

    private val PRICE = Regex("""^(.*?)[\s,:=\-–—]*(?:₹|rs\.?|inr)?\s*(\d+(?:\.\d+)?)\s*(?:/-)?\s*(?:(?:/|per|a|each)\s*([\p{L}]+))?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * One item per line, the way a shopkeeper would write it down:
     * "muslin kurti 450", "Sugar - ₹45/kg", "Dolo 650, 30". A line with no
     * price is still an item name worth knowing.
     */
    fun parse(text: String): List<CatalogueItem> = text.lines().mapNotNull { raw ->
        val line = raw.trim().trimStart('-', '*', '•').trim()
        if (line.isEmpty()) return@mapNotNull null
        val m = PRICE.matchEntire(line)
        val name = m?.groupValues?.get(1)?.trim()?.trimEnd(',', '-', ':', '=')?.trim()
        if (m != null && !name.isNullOrBlank()) {
            CatalogueItem(name, m.groupValues[2].toDouble(), m.groupValues[3].ifBlank { null })
        } else {
            CatalogueItem(line)
        }
    }

    /** The inverse of [parse], for putting the list back in the edit box. */
    fun format(items: List<CatalogueItem>): String = items.joinToString("\n") { item ->
        val price = item.price?.let { p ->
            " " + (if (p == Math.floor(p)) p.toLong().toString() else p.toString()) + (item.unit?.let { "/$it" } ?: "")
        }.orEmpty()
        item.name + price
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The wire shape shared with the server, so the dashboard can edit it too. */
@Serializable
data class CataloguePayload(
    val items: List<CatalogueItem> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = 0L,
)
