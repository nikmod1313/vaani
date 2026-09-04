package app.vaaniledger.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.net.ApiClient
import app.vaaniledger.net.IngestRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/**
 * Lane 1 -- WhatsApp's own "Export chat".
 *
 * Worth having for three reasons: it is WhatsApp's feature rather than a
 * scrape of its UI, it hands over the WHOLE conversation with real
 * timestamps and senders, and it needs no permission at all.
 *
 * Reached two ways, which is why the intent handling below is broader than a
 * share target usually needs:
 *
 *   1. WhatsApp's share sheet (ACTION_SEND), as text or as a .txt/.zip file
 *   2. The "Export file import karein" button, which opens a file picker
 *      (ACTION_VIEW on a content:// Uri)
 *
 * Re-importing the same export is expected, not an error -- shopkeepers
 * export the whole chat every time, because WhatsApp offers no "since" option.
 * The watermark is what makes that safe: every message carries a content
 * hash, approved orders record theirs, and an order whose messages are all
 * known is never offered again.
 */
class ImportExportActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = readContent(intent)
        if (content.isNullOrBlank()) {
            toast(captureStrings().noMessagesFound)
            finish()
            return
        }

        toast(captureStrings().importReading)
        ingest(content, chatIdFor(intent))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------

    /** Pull the export text out of whichever shape the intent arrived in. */
    private fun readContent(intent: Intent?): String? {
        if (intent == null) return null

        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }

        val uri: Uri? = intent.data
            ?: @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

        return uri?.let { readUri(it) }
    }

    /**
     * Read a .txt, or the first .txt inside a .zip.
     *
     * "Export chat -> With media" produces a zip. Refusing it would send the
     * shopkeeper back to WhatsApp to repeat the export the other way, and
     * they will not know why the first one was wrong.
     */
    private fun readUri(uri: Uri): String? = runCatching {
        val name = displayName(uri).orEmpty().lowercase()
        contentResolver.openInputStream(uri)?.use { stream ->
            if (name.endsWith(".zip") || looksZipped(uri)) {
                ZipInputStream(stream).use { zip ->
                    generateSequence { zip.nextEntry }
                        .firstOrNull { it.name.endsWith(".txt", ignoreCase = true) }
                        ?.let { zip.readBytes().decodeToString() }
                }
            } else {
                stream.readBytes().decodeToString()
            }
        }
    }.onFailure { android.util.Log.e(TAG, "readUri failed", it) }.getOrNull()

    private fun looksZipped(uri: Uri): Boolean =
        contentResolver.getType(uri)?.contains("zip") == true

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    /**
     * A stable id for the chat this export came from.
     *
     * The cursor hangs off this, so it has to be the same string every time
     * the same conversation is imported. WhatsApp names exports "WhatsApp
     * Chat with Rahul Verma.txt", which is stable across exports of one chat
     * and different between chats -- exactly the property needed.
     *
     * Falling back to a single shared id when there is no filename is
     * deliberate: one over-broad watermark can only ever SKIP an order the
     * shopkeeper then re-enters by hand, while a per-import random id would
     * make every re-import offer everything again.
     */
    private fun chatIdFor(intent: Intent?): String {
        val uri = intent?.data
            ?: @Suppress("DEPRECATION") intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val name = uri?.let { displayName(it) }
            ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT)
        return "export:" + (name?.removeSuffix(".txt")?.trim()?.lowercase() ?: "unnamed")
    }

    private fun ingest(content: String, chatId: String) {
        scope.launch {
            val db = LedgerDatabase.get(this@ImportExportActivity)
            val known = runCatching { db.customers().names(ShopSettings.ID) }
                .getOrDefault(emptyList())

            val attempt = runCatching {
                ApiClient.api(this@ImportExportActivity).ingestExport(
                    IngestRequest(
                        chatId = chatId,
                        content = content,
                        cursor = ReviewQueue.cursorFor(this@ImportExportActivity, chatId),
                        knownCustomers = known,
                        shopId = ShopSettings.ID,
                        category = ShopSettings.category(this@ImportExportActivity).wire,
                        // The shop's own name, so the server can tell which
                        // participant is the customer without asking.
                        shopAliases = listOf(ShopSettings.NAME),
                        lane = "conversation",
                    )
                )
            }.onFailure { android.util.Log.e(TAG, "ingest failed", it) }

            val response = attempt.getOrNull()

            withContext(Dispatchers.Main) {
                if (response == null) {
                    toast(explain(attempt.exceptionOrNull()))
                    finish()
                    return@withContext
                }

                ReviewQueue.offer(
                    response.orders.map {
                        ReviewQueue.Pending(it, chatId, it.customer)
                    },
                    (if (ShopSettings.language(this@ImportExportActivity) == app.vaaniledger.data.AppLanguage.EN)
                        "From export · ${response.newMessages} new messages"
                    else
                        "Export se · ${response.newMessages} naye message"),
                )
                startActivity(Intent(this@ImportExportActivity, ReviewActivity::class.java))
                finish()
            }
        }
    }

    /**
     * Turn a failure into something the shopkeeper can act on.
     *
     * Everything used to collapse into "Server nahi mila", including a 400
     * the server had answered perfectly clearly. That is the worst kind of
     * error message: it names a cause that is not the cause, and sends
     * whoever reads it to check the network when the real problem was the
     * file they picked.
     *
     * A rejected request and an unreachable server are genuinely different
     * problems with different fixes, so they get different sentences.
     */
    private fun explain(error: Throwable?): String {
        val t = captureStrings()
        return when (error) {
            is retrofit2.HttpException -> {
                val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
                android.util.Log.w(TAG, "server rejected the import: $body")
                when {
                    body == null -> t.importServerRejected(error.code())
                    body.contains("not_a_whatsapp_export") -> t.importNotAnExport
                    body.contains("customer_unknown") -> t.importCustomerUnknown
                    else -> t.importServerRejected(error.code())
                }
            }
            is java.io.IOException -> t.importServerUnreachable
            null -> t.importFailed
            else -> "${t.importFailed}: ${error.javaClass.simpleName}"
        }
    }

    private fun captureStrings() = Strings.of(ShopSettings.language(this)).capture

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "VaaniImport"
    }
}
