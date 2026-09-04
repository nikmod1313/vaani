package app.vaaniledger.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

/**
 * Turns an accessibility node tree into chat messages.
 *
 * Split out of the service so it can be reasoned about (and eventually
 * unit-tested) on its own, and so the same logic serves WhatsApp, WhatsApp
 * Business and — during development — any other chat app we can actually get
 * on an emulator.
 *
 * THE HARD PART, honestly stated: WhatsApp's view ids are not a public
 * contract. They change between versions, and a heuristic tuned to one build
 * silently reads zero messages on the next. So this deliberately leans on
 * SHAPE rather than ids:
 *
 *   - message text lives in a scrollable list
 *   - each bubble is a leaf TextView with real text
 *   - outgoing sits right of centre, incoming left
 *   - timestamps look like times, and are excluded
 *
 * Ids are used when present, as a fast path, never as the only path.
 */
object ChatScraper {

    data class Message(
        val text: String,
        val isOutgoing: Boolean,
        val timeLabel: String?,
    ) {
        val hash: String
            get() = MessageDigest.getInstance("SHA-1")
                .digest("$isOutgoing|$timeLabel|${text.trim().lowercase()}".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    /** Chrome that is never a message, whatever it looks like structurally. */
    private val CHROME_IDS = listOf(
        "toolbar", "entry", "action_bar", "title", "subtitle", "search",
        "tab", "menu", "fab", "header", "footer", "nav", "status",
        "send", "attach", "emoji", "voice_note", "compose",
    )

    // Android puts U+202F (narrow no-break space) before AM/PM, and \s does
    // NOT match it — so "11:07 PM" slipped through a \s?-based pattern.
    private val TIME_PATTERN =
        Regex("""^\d{1,2}:\d{2}([\s\u202f\u00a0]?[apAP]\.?[mM]\.?)?$""")
    private val DATE_PATTERN = Regex(
        """^(today|yesterday|aaj|kal|\d{1,2}/\d{1,2}/\d{2,4}|""" +
            """(mon|tue|wed|thu|fri|sat|sun)\w*|\w+ \d{1,2}, \d{4})$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Chrome that survives the structural filters, found by running this
     * against a real chat app rather than imagining what it would return.
     *
     * Split by match strategy on purpose. Short generic words must only ever
     * match the WHOLE label — a substring match on "message" or "save" would
     * silently drop "save 500 rupaye" or any order mentioning a message, and
     * a dropped order is invisible to the user.
     */
    private val CHROME_EXACT = listOf(
        "text message", "type a message", "message", "send message",
        "report spam", "add contact", "block", "save", "unread",
        "delete", "cancel", "ok", "yes", "no",
    )

    /**
     * Long, distinctive banner strings. Safe to match anywhere in the text
     * because no real order contains them.
     */
    private val CHROME_CONTAINS = listOf(
        "texting with", "end-to-end encrypted", "messages and calls",
        "saving this number will add", "you deleted this message",
        "this message was deleted", "media omitted", "tap to download",
        "missed voice call", "missed video call",
        // Group chats render their own system chrome as plain, non-clickable
        // TextViews sitting right in the message list -- "Isha added you",
        // "2 members, 1 contact - Group created today by Isha", "Add
        // description..." -- indistinguishable from a real message by any
        // structural signal available here. A capture on a group chat
        // concatenated this straight into the text sent for extraction.
        "added you", "group created", "created this group",
        "changed the subject", "changed this group's icon",
        "changed the group description", "you're now an admin",
        "joined using this group", "left the group", "add description",
        "add members", "add your member tag",
    )

    /** "2 members, 1 contact" -- group metadata, wherever it appears in a line. */
    private val GROUP_METADATA_PATTERN =
        Regex("""\d+\s+members?,?\s*\d*\s*contacts?""", RegexOption.IGNORE_CASE)

    /** A bare phone number is a chat title, not an order. */
    private val PHONE_ONLY = Regex("""^[+(]?[\d\s()+-]{7,20}$""")

    /**
     * Extract everything that looks like a message from a window.
     *
     * @param screenWidth used to decide incoming vs outgoing by bubble side.
     */
    fun scrape(root: AccessibilityNodeInfo?, screenWidth: Int): List<Message> {
        if (root == null) return emptyList()

        // Scope to the message list before reading anything.
        //
        // This is the durable filter: the toolbar, the compose box and any
        // banner all live OUTSIDE the scrollable list, so scoping to it
        // removes them structurally rather than by matching their wording —
        // which is localised, and ours is not. String matching below only
        // has to handle what genuinely sits inside the list.
        val scope = findScrollable(root) ?: root

        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        collect(scope, candidates)

        // Timestamps sit beside their bubble; pair each message with the
        // nearest one below-right of it rather than trusting sibling order,
        // which differs between apps.
        val times = candidates.filter { TIME_PATTERN.matches(it.second) }
        val bodies = candidates.filter { isBody(it.first, it.second) }

        // Sort by vertical position, NOT traversal order. Chat lists are
        // commonly laid out reversed, so walking the tree yields newest
        // first — which silently reverses every multi-message order before
        // it reaches the engine.
        return bodies
            .map { (node, text) -> Rect().also { node.getBoundsInScreen(it) } to text }
            .sortedBy { (r, _) -> r.top }
            .map { (r, text) ->
                Message(
                    text = text,
                    isOutgoing = r.centerX() > screenWidth / 2,
                    timeLabel = nearestTime(r, times),
                )
            }
    }

    /**
     * The conversation title — the contact name, which is the strongest
     * customer signal available and free.
     *
     * It sits ABOVE the message list, so take the topmost text node that is
     * not inside the list itself.
     */
    fun chatTitle(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val list = findScrollable(root)
        val listTop = list?.let { Rect().also { r -> it.getBoundsInScreen(r) }.top } ?: Int.MAX_VALUE

        val all = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        collect(root, all)

        return all
            .map { (node, text) -> Rect().also { node.getBoundsInScreen(it) } to text }
            .filter { (r, t) ->
                r.top < listTop && r.top > 0 && t.length in 2..40 && !TIME_PATTERN.matches(t)
            }
            .minByOrNull { (r, _) -> r.top }
            ?.second
            ?.takeIf { !PHONE_ONLY.matches(it) }   // a number is not a name
    }

    /** The message list. Everything outside it is chrome. */
    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    /**
     * Every node carrying text, with its text. Deliberately permissive --
     * filtering happens after, where the reasoning is visible.
     */
    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<AccessibilityNodeInfo, String>>,
    ) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(node to text)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out)
        }
    }

    private fun isBody(node: AccessibilityNodeInfo, text: String): Boolean {
        val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
        val lower = text.lowercase().trim()

        // EXCLUSIONS FIRST. An earlier version put the id "fast path" at the
        // top and let a matching id skip every check — which was wrong twice
        // over on a real device:
        //
        //   compose_message_text  the compose box, CONTAINS "message_text"
        //   message_text          also used for the timestamp row AND the
        //                         "Texting with ..." header
        //
        // So an id is a hint about what something might be, never a licence
        // to stop checking what it actually is.

        if (node.isEditable) return false                     // compose box
        if (node.childCount > 0) return false                 // container, not a leaf
        if (text.length < 2) return false
        if (TIME_PATTERN.matches(text) || DATE_PATTERN.matches(text)) return false
        if (PHONE_ONLY.matches(text)) return false
        if (CHROME_EXACT.any { lower == it }) return false
        if (CHROME_CONTAINS.any { lower.contains(it) }) return false
        if (GROUP_METADATA_PATTERN.containsMatchIn(text)) return false
        if (CHROME_IDS.any { id.contains(it) }) return false
        if (node.isClickable && text.length < 25) return false // action label

        // Only now is the id worth anything: it promotes a node that already
        // survived every exclusion. Exact match, since `contains` is what let
        // compose_message_text through.
        if (id == "message_text" || id == "conversation_text") return true

        return node.className?.toString()?.contains("TextView") == true
    }

    private fun nearestTime(
        bounds: Rect,
        times: List<Pair<AccessibilityNodeInfo, String>>,
    ): String? = times
        .map { (node, text) -> Rect().also { node.getBoundsInScreen(it) } to text }
        .filter { (r, _) -> kotlin.math.abs(r.centerY() - bounds.centerY()) < bounds.height() * 2 }
        .minByOrNull { (r, _) -> kotlin.math.abs(r.centerY() - bounds.centerY()) }
        ?.second

    /**
     * Full tree dump, for finding out what a new app or a new WhatsApp
     * version actually looks like.
     *
     * When capture starts returning nothing after a WhatsApp update, this is
     * the tool that tells you why in about a minute.
     */
    fun dump(node: AccessibilityNodeInfo?, depth: Int = 0, out: StringBuilder = StringBuilder()): String {
        if (node == null) return out.toString()
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val text = node.text?.toString()?.take(45)
        val id = node.viewIdResourceName?.substringAfterLast('/')

        out.append("  ".repeat(depth))
            .append(node.className?.toString()?.substringAfterLast('.'))
            .append(" id=").append(id ?: "-")
            .append(" scroll=").append(node.isScrollable)
            .append(" x=").append(bounds.centerX())
            .append(if (text != null) " text=\"$text\"" else "")
            .append('\n')

        for (i in 0 until node.childCount) {
            dump(node.getChild(i), depth + 1, out)
        }
        return out.toString()
    }
}
