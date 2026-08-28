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

    /**
     * The last chat app we saw an event from.
     *
     * `rootInActiveWindow` returns null while our own overlay holds focus, so
     * checking the live package refused perfectly good captures. Remembering
     * the last real chat keeps the guard strict without breaking the case it
     * was written for.
     */
    @Volatile
    var lastChatPackage: String? = null
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (app.vaaniledger.BuildConfig.DEBUG) {
            android.util.Log.v(TREE_TAG, "event from $pkg type=${event?.eventType}")
        }
        pkg?.takeIf { it in SUPPORTED_CHAT_PACKAGES }?.let { lastChatPackage = it }

        // Beyond noting which chat is open, only react while actively
        // harvesting. Reacting to every event would be background scraping,
        // which is exactly what we promised not to do.
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

    /**
     * Log the whole node tree.
     *
     * When capture starts returning nothing after a WhatsApp update, this
     * tells you why in about a minute:
     *
     *     adb shell am broadcast -a app.vaaniledger.DUMP_TREE
     *     adb logcat -d -s VaaniTree
     */
    /**
     * The root node of a visible, supported chat window.
     *
     * This deliberately does NOT rely on `rootInActiveWindow`. Two failure
     * modes taught us why:
     *
     *  1. It hands back whatever window is focused — including our own
     *     setup screen, whose UI text capture then offered to save as an
     *     order. `packageNames` in accessibility_config limits which apps
     *     send us EVENTS; it does not scope that call.
     *  2. It is null in the moments after our own overlay releases focus,
     *     and `lastChatPackage` (set from events) is also null when the
     *     service binds while the chat is already open and idle — a static
     *     screen fires no events. Result: valid captures refused.
     *
     * Enumerating `windows` sidesteps both: we look for a chat window by
     * package, wherever focus happens to be. The caller must recycle.
     */
    fun chatRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() in SUPPORTED_CHAT_PACKAGES) return root
            @Suppress("DEPRECATION") root.recycle()
        }
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() in SUPPORTED_CHAT_PACKAGES) return root
            @Suppress("DEPRECATION") root.recycle()
        }
        return null
    }

    /** Is a supported chat app on screen at all? */
    fun hasChatWindow(): Boolean {
        val root = chatRoot() ?: return false
        @Suppress("DEPRECATION") root.recycle()
        return true
    }

    /** The contact name of the chat on screen, if we can read it. */
    fun currentChatTitle(): String? {
        val root = chatRoot() ?: return null
        return try {
            ChatScraper.chatTitle(root)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    fun dumpTree(): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        return try {
            ChatScraper.dump(root)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun collectVisible() {
        val root = chatRoot() ?: return
        try {
            val width = resources.displayMetrics.widthPixels
            if (app.vaaniledger.BuildConfig.DEBUG) {
                android.util.Log.d(TREE_TAG, ChatScraper.dump(root))
            }
            for (m in ChatScraper.scrape(root, width)) {
                collected[m.hash] = CapturedMessage(m.text, m.isOutgoing, m.timeLabel)
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
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

    companion object {
        @Volatile
        var instance: LedgerAccessibilityService? = null
            private set

        private const val SCROLL_SETTLE_MS = 260L
        private const val TREE_TAG = "VaaniTree"

        /**
         * Keep in step with accessibility_config.xml. Google Messages is
         * present for development only — remove both before shipping.
         */
        val SUPPORTED_CHAT_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
        )
        private val TIME_PATTERN = Regex("""\d{1,2}:\d{2}(\s?[apAP]\.?[mM]\.?)?""")
    }
}
