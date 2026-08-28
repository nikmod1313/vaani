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
    val confidence: Confidence,
    val source: String = "rules",
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
)

@Serializable
data class IngestResponse(
    @SerialName("total_messages") val totalMessages: Int,
    @SerialName("new_messages") val newMessages: Int,
    val blocks: Int,
    val dropped: Int,
    @SerialName("drop_rate") val dropRate: Double,
    val candidates: List<Extraction>,
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


interface VaaniApi {

    @GET("health")
    suspend fun health(): Health

    @POST("extract")
    suspend fun extract(@Body request: ExtractRequest): Extraction

    @POST("extract/batch")
    suspend fun extractBatch(@Body request: BatchRequest): BatchResponse

    @POST("ingest/export")
    suspend fun ingestExport(@Body request: IngestRequest): IngestResponse

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
