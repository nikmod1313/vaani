package app.vaaniledger.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

/**
 * Reads WhatsApp chat text directly from the view hierarchy.
 *
 * Two jobs:
 *   1. Single capture — "Yeh likh lo" reads what is on screen.
 *   2. Scroll-harvest — "Entry kar do" walks backwards through the chat,
 *      collecting messages until it hits the watermark or the date cut-off.
 *
 * Preferred over screenshot + OCR because the text is exact and FLAG_SECURE
 * does not affect it. Screenshot capture stays as the universal fallback.
 *
 * PRIVACY: scoped to WhatsApp in accessibility_config.xml, and only ever
 * invoked by an explicit user action. Nothing runs in the background, and
 * text is discarded unless the entry is saved.
 */
class LedgerAccessibilityService : AccessibilityService() {

    /** Set while a scroll-harvest is running, so we know to keep reading. */
    @Volatile
    private var harvesting = false

    private val collected = LinkedHashMap<String, CapturedMessage>()

    data class CapturedMessage(
        val text: String,
        val isOutgoing: Boolean,
        val timeLabel: String?,
    ) {
        /** Stable identity, mirroring the backend's content hash. */
        val hash: String
            get() = MessageDigest.getInstance("SHA-1")
                .digest("$isOutgoing|$timeLabel|${text.trim().lowercase()}".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only react while actively harvesting. Reacting to every event would
        // be background scraping, which is exactly what we promised not to do.
        if (!harvesting) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            collectVisible()
        }
    }

    override fun onInterrupt() {
        harvesting = false
    }

    // ----------------------------------------------------------------------
    // Single capture
    // ----------------------------------------------------------------------

    /** Read everything currently on screen. Backs the "Yeh likh lo" action. */
    fun captureVisible(): List<CapturedMessage> {
        collected.clear()
        collectVisible()
        return collected.values.toList()
    }

    private fun collectVisible() {
        val root = rootInActiveWindow ?: return
        try {
            walk(root) { node ->
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrEmpty() && looksLikeMessage(node, text)) {
                    val message = CapturedMessage(
                        text = text,
                        isOutgoing = isOutgoing(node),
                        timeLabel = siblingTimeLabel(node),
                    )
                    collected[message.hash] = message
                }
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Distinguish a message bubble from chrome.
     *
     * WhatsApp's view ids are not part of any public contract and DO change
     * between versions, so this deliberately leans on structure and content
     * rather than hardcoded ids alone. Re-verify against the WhatsApp build
     * on the demo phone in Week 0 — this is the most version-fragile code in
     * the project.
     */
    private fun looksLikeMessage(node: AccessibilityNodeInfo, text: String): Boolean {
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith("message_text") || id.endsWith("conversation_text")) return true

        // Fall back to shape: a TextView inside a list row, not a toolbar.
        val isText = node.className?.contains("TextView") == true
        val notChrome = !id.contains("toolbar") && !id.contains("entry")
        return isText && notChrome && text.length > 1
    }

    private fun isOutgoing(node: AccessibilityNodeInfo): Boolean {
        // Outgoing bubbles sit on the right. Compare the bubble's centre
        // against the screen midpoint rather than trusting a view id.
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val screenWidth = resources.displayMetrics.widthPixels
        return bounds.centerX() > screenWidth / 2
    }

    private fun siblingTimeLabel(node: AccessibilityNodeInfo): String? {
        val parent = node.parent ?: return null
        try {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                val text = child.text?.toString()?.trim()
                if (text != null && TIME_PATTERN.matches(text)) return text
            }
        } finally {
            parent.recycle()
        }
        return null
    }

    // ----------------------------------------------------------------------
    // Scroll-harvest — backs "Entry kar do"
    // ----------------------------------------------------------------------

    /**
     * Walk backwards through the chat collecting messages.
     *
     * Stops on any of: the watermark hash appearing, [maxScrolls] reached, or
     * two consecutive scrolls yielding nothing new. That last guard is what
     * prevents an infinite loop at the top of a conversation.
     */
    fun harvest(
        stopAtHash: String? = null,
        maxScrolls: Int = 40,
        onComplete: (List<CapturedMessage>) -> Unit,
    ) {
        collected.clear()
        harvesting = true

        var scrolls = 0
        var emptyRounds = 0

        fun step() {
            if (!harvesting || scrolls >= maxScrolls || emptyRounds >= 2) {
                harvesting = false
                onComplete(collected.values.reversed())
                return
            }

            val before = collected.size
            collectVisible()

            if (stopAtHash != null && collected.containsKey(stopAtHash)) {
                harvesting = false
                onComplete(collected.values.reversed())
                return
            }

            emptyRounds = if (collected.size == before) emptyRounds + 1 else 0
            scrolls++

            val list = findScrollable(rootInActiveWindow)
            if (list == null) {
                harvesting = false
                onComplete(collected.values.reversed())
                return
            }

            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            list.recycle()

            // Give WhatsApp time to render before reading again.
            android.os.Handler(mainLooper).postDelayed(::step, SCROLL_SETTLE_MS)
        }

        step()
    }

    private fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val found = findScrollable(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, visit)
            child.recycle()
        }
    }

    companion object {
        @Volatile
        var instance: LedgerAccessibilityService? = null
            private set

        private const val SCROLL_SETTLE_MS = 260L
        private val TIME_PATTERN = Regex("""\d{1,2}:\d{2}(\s?[apAP]\.?[mM]\.?)?""")
    }
}
