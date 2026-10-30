package app.vaaniledger.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.extract.ConversationResolver
import app.vaaniledger.extract.ExportParser
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

    /**
     * Parse, thread and resolve the export ON THE PHONE.
     *
     * No network call anywhere in this path. That is the property that lets
     * the app be handed to a shopkeeper as a thing that works, rather than a
     * thing that works while a laptop on the same wifi is switched on. The
     * same engine runs on the server for the dashboard, and the parity tests
     * hold the two to identical answers, so going on-device costs nothing in
     * what gets extracted -- only the LLM stage, which needs a key nobody
     * has, stays server-side.
     */
    private fun ingest(content: String, chatIdHint: String) {
        scope.launch {
            val db = LedgerDatabase.get(this@ImportExportActivity)
            val category = ShopSettings.category(this@ImportExportActivity)
            val aliases = setOf(ShopSettings.NAME, ShopSettings.name(this@ImportExportActivity))

            val parsed = ExportParser.parse(content)
            if (parsed.isEmpty()) {
                withContext(Dispatchers.Main) {
                    toast(captureStrings().importNotAnExport)
                    finish()
                }
                return@launch
            }

            val customer = ExportParser.inferCustomer(parsed, aliases)
            if (customer == null) {
                withContext(Dispatchers.Main) {
                    toast(captureStrings().importCustomerUnknown)
                    finish()
                }
                return@launch
            }

            // The watermark: everything already approved or declined from
            // this customer's chat, by either lane.
            val chatId = ReviewQueue.chatIdFor(customer)
            val seen = db.cursors().byChat(chatId)?.hashes ?: emptySet()
            val fresh = parsed.filter { it.contentHash !in seen }

            val known = runCatching { db.customers().names(ShopSettings.ID) }
                .getOrDefault(emptyList())
            val turns = ExportParser.toTurns(fresh, customer, aliases)
            // A reminder with a pay link, sent before a later "payment done".
            val payLinks = parsed
                .filter { ExportParser.isShopkeeper(it.sender, customer, aliases) && app.vaaniledger.reminder.ReminderLinks.carriesPayLink(it.body) }
                .map { it.timestamp }
            val orders = ConversationResolver.resolveAll(turns, customer, category, payLinkSent = payLinks)
                .map { with(ConversationResolver) { it.toExtraction(known, category) } }
                .map { app.vaaniledger.extract.CatalogueMatch.enrich(it, app.vaaniledger.data.Catalogue.items(this@ImportExportActivity)) }
                .map { ReviewQueue.settleFromBalance(this@ImportExportActivity, it, it.customer) }

            android.util.Log.i(
                TAG,
                "export: ${parsed.size} parsed, ${fresh.size} new, ${orders.size} order(s) for $customer",
            )

            withContext(Dispatchers.Main) {
                ReviewQueue.offer(
                    orders.map { ReviewQueue.Pending(it, chatId, it.customer ?: customer) },
                    if (ShopSettings.language(this@ImportExportActivity) == app.vaaniledger.data.AppLanguage.EN)
                        "From export · ${fresh.size} new messages"
                    else
                        "एक्सपोर्ट से · ${fresh.size} नए संदेश",
                )
                startActivity(Intent(this@ImportExportActivity, ReviewActivity::class.java))
                finish()
            }
        }
    }

    private fun captureStrings() =
        app.vaaniledger.data.Strings.of(ShopSettings.language(this)).capture

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "VaaniImport"
    }
}
