package app.vaaniledger.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Share target for WhatsApp's "Export chat".
 *
 * This is lane 1 of bulk ingestion, and the reason it is worth having: the
 * export is WhatsApp's own feature, hands us the whole conversation with
 * timestamps and senders, and needs no permission at all.
 *
 * Transparent and finishes immediately -- the user came from WhatsApp's share
 * sheet and should land back where they were.
 */
class ImportExportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }

        if (text.isNullOrBlank()) {
            toast("Chat khaali mila")
        } else {
            // TODO(week 3): POST to /ingest/export with this chat's cursor and
            // open the batch review queue with the candidates it returns.
            val lines = text.lineSequence().count { it.isNotBlank() }
            toast("$lines lines mile — review queue week 3 mein")
        }
        finish()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
