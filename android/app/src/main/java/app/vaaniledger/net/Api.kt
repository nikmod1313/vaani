package app.vaaniledger.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The API surface, matching `docs/api.yaml`.
 *
 * Field names use @SerialName to pin the wire format to the contract's
 * snake_case. Do not rely on the Kotlin property name matching -- renaming a
 * property would silently change the payload and break sync.
 */

// --------------------------------------------------------------------------
// Extraction
// --------------------------------------------------------------------------

@Serializable
data class ExtractRequest(
    val text: String,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("known_customers") val knownCustomers: List<String> = emptyList(),
    @SerialName("shop_id") val shopId: String? = null,
    val source: String = "chat",
    /** Overrides the shop's stored category. Normally left null. */
    val category: String? = null,
)

/**
 * One line of an order.
 *
 * `unitPrice` is only ever set when the message STATED a rate. It is never
 * derived by dividing an amount by a quantity -- that invents a per-unit
 * price nobody agreed to, and it is the number a customer will argue about.
 */
@Serializable
data class LineItem(
    val name: String = "",
    val modifier: String? = null,
    @SerialName("display_name") val displayName: String = "",
    val quantity: Double? = null,
    val unit: String? = null,
    @SerialName("unit_price") val unitPrice: Double? = null,
    @SerialName("line_total") val lineTotal: Double? = null,
    val label: String = "",
)

@Serializable
data class FieldScore(val score: Double, val reason: String)

@Serializable
data class Confidence(
    val overall: Double,
    /** "auto" | "ask" | "manual" -- decides what the confirm sheet does. */
    val route: String,
    val fields: Map<String, FieldScore> = emptyMap(),
    @SerialName("ask_about") val askAbout: List<String> = emptyList(),
)

@Serializable
data class Extraction(
    val text: String,
    val customer: String? = null,
    @SerialName("customer_is_new") val customerIsNew: Boolean = false,
    val amount: Double? = null,
    val mode: String = "unknown",
    val direction: String = "unknown",
    val items: List<String> = emptyList(),
    @SerialName("line_items") val lineItems: List<LineItem> = emptyList(),
    val confidence: Confidence,
    val source: String = "rules",

    // ---- conversation lane only ----------------------------------------
    //
    // Defaulted so the same type still parses a plain /extract response.

    /** The tidied sentence the engine read. Shown as provenance, not as evidence. */
    @SerialName("canonical_text") val canonicalText: String? = null,
    /** When the customer committed, ISO-8601. Drives occurredAt on the row. */
    @SerialName("occurred_at") val occurredAt: String? = null,
    /**
     * Hashes of every message this order was built from. Recorded in the
     * chat cursor ONLY after the shopkeeper approves, which is what makes a
     * re-import skip it instead of booking it twice.
     */
    @SerialName("content_hashes") val contentHashes: List<String> = emptyList(),
    /** Why the resolver believes this is an order. Shown in the review card. */
    val signals: List<String> = emptyList(),
    /** The item came from an earlier turn than the commitment. */
    @SerialName("carried_topic") val carriedTopic: Boolean = false,
)

@Serializable
data class Block(
    val text: String,
    @SerialName("sender_name") val senderName: String? = null,
)

@Serializable
data class BatchRequest(
    val blocks: List<Block>,
    @SerialName("known_customers") val knownCustomers: List<String> = emptyList(),
    @SerialName("shop_id") val shopId: String? = null,
)

@Serializable
data class BatchResponse(val results: List<Extraction>)


// --------------------------------------------------------------------------
// Bulk ingestion
// --------------------------------------------------------------------------

@Serializable
data class CursorPayload(
    @SerialName("chat_id") val chatId: String,
    @SerialName("last_timestamp") val lastTimestamp: String? = null,
    @SerialName("seen_hashes") val seenHashes: List<String> = emptyList(),
)

@Serializable
data class IngestRequest(
    @SerialName("chat_id") val chatId: String,
    val content: String,
    val since: String? = null,
    val until: String? = null,
    val cursor: CursorPayload? = null,
    @SerialName("known_customers") val knownCustomers: List<String> = emptyList(),
    @SerialName("shop_id") val shopId: String? = null,
    val category: String? = null,
    /**
     * The other party in the chat. Supplying it switches the server to the
     * conversation lane, the only one that can resolve an order split across
     * turns ("blue kurti?" ... "mujhe 3 chahiye").
     */
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("shop_aliases") val shopAliases: List<String> = emptyList(),
    val lane: String? = null,
)

@Serializable
data class IngestResponse(
    /** Which lane answered: "conversation" or "blocks". */
    val lane: String = "",
    val category: String = "",
    @SerialName("total_messages") val totalMessages: Int = 0,
    @SerialName("new_messages") val newMessages: Int = 0,
    val threads: Int = 0,
    val blocks: Int = 0,
    val dropped: Int = 0,
    @SerialName("drop_rate") val dropRate: Double = 0.0,
    /** Conversation lane. Prefer this. */
    val orders: List<Extraction> = emptyList(),
    /** Block lane. Empty whenever `orders` is populated -- never both. */
    val candidates: List<Extraction> = emptyList(),
)


// --------------------------------------------------------------------------
// Live screen capture
// --------------------------------------------------------------------------

@Serializable
data class ScreenMessage(
    val body: String,
    val sender: String? = null,
    /** ISO-8601. Null when the bubble carried no readable time label. */
    val timestamp: String? = null,
    @SerialName("is_outgoing") val isOutgoing: Boolean = false,
    @SerialName("content_hash") val contentHash: String? = null,
)

@Serializable
data class ScreenRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("customer_name") val customerName: String,
    val messages: List<ScreenMessage> = emptyList(),
    val cursor: CursorPayload? = null,
    @SerialName("shop_id") val shopId: String? = null,
    val category: String? = null,
    @SerialName("known_customers") val knownCustomers: List<String> = emptyList(),
)

@Serializable
data class ScreenResponse(
    val lane: String = "",
    val category: String = "",
    @SerialName("total_messages") val totalMessages: Int = 0,
    @SerialName("new_messages") val newMessages: Int = 0,
    val threads: Int = 0,
    val orders: List<Extraction> = emptyList(),
)

@Serializable
data class CategoryOption(val id: String, val label: String)

@Serializable
data class CategoriesResponse(
    val categories: List<CategoryOption> = emptyList(),
    val default: String = "general",
)


// --------------------------------------------------------------------------
// Sync
// --------------------------------------------------------------------------

@Serializable
data class TransactionPayload(
    /** Client-generated. This is the idempotency key. */
    val id: String,
    val amount: Double,
    val mode: String,
    val direction: String = "sale",
    @SerialName("customer_name") val customerName: String? = null,
    val items: List<String> = emptyList(),
    val source: String = "manual",
    val confidence: Double = 0.0,
    @SerialName("occurred_at") val occurredAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class SyncRequest(
    @SerialName("shop_id") val shopId: String,
    @SerialName("shop_name") val shopName: String? = null,
    /**
     * Sent on setup and whenever it changes, not on every push. The server
     * only overwrites a stored category when this is non-null, so an ordinary
     * sync cannot reset a choice the shopkeeper already made.
     */
    @SerialName("shop_category") val shopCategory: String? = null,
    val since: String? = null,
    val transactions: List<TransactionPayload> = emptyList(),
)

@Serializable
data class SyncResponse(
    val accepted: Int,
    val duplicates: Int,
    /**
     * Rows the server changed. Kept as raw JSON because the server sends
     * mixed types — amount is a number, deleted a boolean — and typing this
     * as Map<String, String?> made every sync fail to parse AFTER the server
     * had already accepted the write. The entry was saved and the phone still
     * showed it as pending.
     *
     * Pull-down is not implemented yet, so nothing reads these.
     */
    val transactions: List<JsonElement> = emptyList(),
)


// --------------------------------------------------------------------------
// Summary
// --------------------------------------------------------------------------

@Serializable
data class Narration(
    val text: String,
    @SerialName("language_code") val languageCode: String,
    val characters: Int = 0,
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("cache_hit") val cacheHit: Boolean = false,
    /** "on-device" when Sarvam is unavailable -- use Android TextToSpeech. */
    val fallback: String? = null,
)

@Serializable
data class CorrectionRequest(
    @SerialName("shop_id") val shopId: String,
    @SerialName("raw_text") val rawText: String,
    val predicted: Map<String, String> = emptyMap(),
    val corrected: Map<String, String>,
)

@Serializable
data class CorrectionResponse(
    /**
     * Typed to what the server actually sends — stored is an INT and
     * weakest_fields maps to INTs. The sync path already taught us this
     * lesson: typing a response as strings when the server sends numbers
     * fails AFTER the request succeeded, which is the most confusing place
     * a bug can live.
     */
    val stored: Int = 0,
    @SerialName("weakest_fields") val weakestFields: Map<String, Int> = emptyMap(),
)

@Serializable
data class Health(
    val status: String,
    val version: String,
    /** Reports "unavailable (rules-only)" when there is no model key. */
    val stage2: String = "",
    val tts: String = "",
)


/** The phone typing the code the dashboard shows. */
@Serializable
data class PairClaim(
    val code: String,
    @SerialName("shop_id") val shopId: String,
    @SerialName("shop_name") val shopName: String? = null,
    @SerialName("shop_category") val shopCategory: String? = null,
)

@Serializable
data class PairResult(val ok: Boolean, val message: String? = null)

interface VaaniApi {

    @POST("pair/claim")
    suspend fun pairClaim(@Body request: PairClaim): PairResult

    /** Last writer wins; the reply is whichever list is newer. */
    @retrofit2.http.PUT("shops/{shop}/catalogue")
    suspend fun putCatalogue(
        @retrofit2.http.Path("shop") shopId: String,
        @Body catalogue: app.vaaniledger.data.CataloguePayload,
    ): app.vaaniledger.data.CataloguePayload

    @GET("health")
    suspend fun health(): Health

    @POST("extract")
    suspend fun extract(@Body request: ExtractRequest): Extraction

    @POST("extract/batch")
    suspend fun extractBatch(@Body request: BatchRequest): BatchResponse

    @POST("ingest/export")
    suspend fun ingestExport(@Body request: IngestRequest): IngestResponse

    @POST("ingest/screen")
    suspend fun ingestScreen(@Body request: ScreenRequest): ScreenResponse

    @GET("categories")
    suspend fun categories(): CategoriesResponse

    @POST("sync")
    suspend fun sync(@Body request: SyncRequest): SyncResponse

    @GET("summary/narrate")
    suspend fun narrate(
        @Query("shop_id") shopId: String,
        @Query("lang") lang: String = "hi-IN",
        @Query("speaker") speaker: String = "anushka",
    ): Narration

    @POST("corrections")
    suspend fun correction(@Body request: CorrectionRequest): CorrectionResponse
}
