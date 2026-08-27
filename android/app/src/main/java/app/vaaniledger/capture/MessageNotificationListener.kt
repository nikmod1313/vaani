package app.vaaniledger.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Lane 3 of ingestion: stage every incoming WhatsApp message as it arrives,
 * so the backlog is ready before the shopkeeper asks for it.
 *
 * Reads notifications only -- no chat history, nothing sent. Staged rows are
 * deduped by content hash before anything is extracted.
 */
class MessageNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName !in WHATSAPP) return

        val extras = notification.notification.extras
        val sender = extras.getString("android.title") ?: return
        val body = extras.getCharSequence("android.text")?.toString() ?: return

        // WhatsApp posts a rollup ("3 new messages") alongside the real ones.
        if (body.matches(Regex("""\d+ new messages?"""))) return

        // TODO(week 4): write to StagedMessageDao with the content hash.
        Log.d(TAG, "staged from $sender: ${body.take(60)}")
    }

    companion object {
        private const val TAG = "VaaniStaging"
        private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
