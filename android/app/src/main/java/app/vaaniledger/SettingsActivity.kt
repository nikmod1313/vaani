package app.vaaniledger

import app.vaaniledger.ui.BottomNav
import app.vaaniledger.ui.Nav
import app.vaaniledger.ui.Scaffold.page

import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.ArtView
import app.vaaniledger.ui.LineIcon
import app.vaaniledger.ui.ManuscriptFrame
import app.vaaniledger.ui.Motion
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.chevron
import app.vaaniledger.ui.Views.dp
import app.vaaniledger.ui.Views.eyebrow
import app.vaaniledger.ui.Views.medallion
import app.vaaniledger.ui.Views.pageHeader
import app.vaaniledger.ui.Views.pressable
import app.vaaniledger.ui.Views.sectionHeading
import app.vaaniledger.ui.Views.text

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.vaaniledger.data.AppLanguage
import app.vaaniledger.data.ShopCategory
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.labelIn
import app.vaaniledger.data.Strings
import app.vaaniledger.net.ServerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Change what onboarding only ever asks once: category, language, and
 * (rarely, for setup / testing) the server address.
 *
 * Deliberately a separate screen from onboarding rather than a re-run of it.
 * Onboarding is a first-run wizard that ends by leaving; settings is a place
 * you come back to on purpose, so it shows the current answer up front
 * instead of walking through a welcome screen again.
 */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ink = Theme.ink
    private val muted = Theme.muted
    private val indigo = Theme.primary
    private val paper = Theme.paper
    private val line = Theme.border

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        setContentView(
            page(
                current = BottomNav.Destination.SETTINGS,
                items = Nav.items(this),
                onNavigate = { Nav.go(this, it) },
                content = root,
            )
        )
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        root.removeAllViews()
        val lang = ShopSettings.language(this)
        val en = lang == AppLanguage.EN
        val t = Strings.of(lang).main

        root.addView(pageHeader(ShopSettings.name(this), t.settingsTitle))

        root.addView(sectionHeading(if (en) "Your shop" else "आपकी दुकान"))
        root.addView(group(
            settingRow(
                LineIcon.Glyph.PENCIL, if (en) "Shop name" else "दुकान का नाम",
                ShopSettings.name(this),
            ) { editShopName() },
            settingRow(
                LineIcon.Glyph.QR, "UPI ID",
                ShopSettings.upiId(this) ?: if (en) "Not set — reminders go without a pay link"
                else "नहीं डाला — याद-संदेश में पेमेंट लिंक नहीं जाएगा",
            ) { editUpiId() },
            settingRow(
                LineIcon.Glyph.BOOK, if (en) "Items & prices" else "सामान और दाम",
                app.vaaniledger.data.Catalogue.items(this).size.let { n ->
                    if (n == 0) (if (en) "Add yours — Vaani then reads them in chats and fills the price"
                    else "अपना सामान डालिए — Vaani चैट में पहचानेगी और दाम भरेगी")
                    else (if (en) "$n items" else "$n चीज़ें")
                },
            ) { editCatalogue() },
            settingRow(
                LineIcon.Glyph.SHOP, if (en) "Shop category" else "दुकान की श्रेणी",
                ShopSettings.category(this).labelIn(lang),
            ) { chooseCategory() },
            settingRow(
                LineIcon.Glyph.GLOBE, if (en) "Language" else "भाषा",
                lang.displayName,
            ) { chooseLanguage() },
        ))

        root.addView(sectionHeading(if (en) "Chats & computer" else "चैट और कंप्यूटर"))
        root.addView(group(
            settingRow(
                LineIcon.Glyph.SERVER, if (en) "Connect a computer" else "कंप्यूटर से जोड़ें",
                if (en) "Type the code shown on the dashboard" else "डैशबोर्ड पर दिखा कोड डालिए",
            ) { pairComputer() },
            settingRow(
                LineIcon.Glyph.QR, if (en) "Dashboard code" else "डैशबोर्ड कोड",
                app.vaaniledger.data.DashboardAccess.code(this)?.let {
                    if (en) "$it — opens your khata on the website" else "$it — इससे वेबसाइट पर आपका खाता खुलता है"
                } ?: if (en) "Make a code to open your khata on the website" else "वेबसाइट पर खाता खोलने के लिए कोड बनाइए",
            ) { dashboardCode() },
            settingRow(
                LineIcon.Glyph.GLOBE, if (en) "Your dashboard" else "आपका डैशबोर्ड",
                if (en) "http://vaani.local:8000 — open it on a computer on this Wi-Fi. Tap to share."
                else "http://vaani.local:8000 — इसी Wi-Fi के कंप्यूटर पर खोलिए। भेजने के लिए टैप करें।",
            ) { shareDashboard() },
            settingRow(
                LineIcon.Glyph.CHAT, if (en) "What Vaani can read" else "Vaani क्या पढ़ सकती है",
                if (en) "What works in a chat, and what to enter by hand" else "चैट में क्या चलता है, क्या हाथ से भरें",
            ) { showCapabilities() },
        ))

        root.addView(sectionHeading(if (en) "Backup" else "खाते का बैकअप"))
        root.addView(group(
            settingRow(
                LineIcon.Glyph.SHARE, if (en) "Export the khata" else "खाता एक्सपोर्ट करें",
                if (en) "Every entry in one spreadsheet — send it to WhatsApp, Drive or email"
                else "सारी एंट्री एक फ़ाइल में — WhatsApp, Drive या ईमेल पर भेजें",
            ) { exportKhata() },
        ))

        root.addView(sectionHeading(if (en) "Advanced" else "उन्नत"))
        root.addView(group(
            settingRow(
                LineIcon.Glyph.SERVER, if (en) "Server address" else "सर्वर का पता",
                ServerSettings.baseUrl(this) +
                    if (ServerSettings.isCustom(this)) "" else "  (default)",
            ) { editServerAddress() },
        ))

        // The colophon: what this is, set at the foot of the page as a
        // printer's mark would be.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(40), 0, dp(8))
            addView(ArtView(this@SettingsActivity, R.drawable.art_sun, alignBottom = false).apply { alpha = 0.85f },
                LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(text("वाणी", 24f, Theme.primary, Type.display(this@SettingsActivity)).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
            addView(text(t.tagline, 13f, muted, Type.displayItalic(this@SettingsActivity)).apply {
                gravity = Gravity.CENTER
            })
            addView(eyebrow("v" + BuildConfig.VERSION_NAME, Theme.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        })
        Motion.stagger(root, step = 40)
    }

    /** Rows that belong together, in one card with hairlines between them. */
    private fun group(vararg rows: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ManuscriptFrame(this@SettingsActivity)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(View(this@SettingsActivity).apply { setBackgroundColor(line) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(58) })
            addView(row)
        }
    }

    private fun settingRow(icon: LineIcon.Glyph, title: String, value: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            pressable()
            setOnClickListener { onClick() }

            addView(medallion(icon, size = 42))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(text(title, 16f, ink, Type.medium(this@SettingsActivity)))
                addView(text(value, 13.5f, muted))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron())
        }

    private fun chooseCategory() {
        val options = ShopCategory.entries
        val labels = options.map { it.labelIn(ShopSettings.language(this)) }.toTypedArray()
        val current = options.indexOf(ShopSettings.category(this))
        AlertDialog.Builder(this)
            .setTitle(Strings.of(ShopSettings.language(this)).onboarding.categoryTitle)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                ShopSettings.setCategory(this, options[which])
                scope.launch { ShopSettings.persist(this@SettingsActivity) }
                dialog.dismiss()
                render()
            }
            .show()
    }

    private fun chooseLanguage() {
        val options = AppLanguage.entries
        val labels = options.map { it.displayName }.toTypedArray()
        val current = options.indexOf(ShopSettings.language(this))
        AlertDialog.Builder(this)
            .setTitle(Strings.of(ShopSettings.language(this)).onboarding.languageTitle)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                ShopSettings.setLanguage(this, options[which])
                scope.launch { ShopSettings.persist(this@SettingsActivity) }
                dialog.dismiss()
                render()
            }
            .show()
    }

    /** One line of text, in the app's own field style, with Save / Cancel. */
    private fun textDialog(
        title: String,
        message: String?,
        initial: String,
        inputType: Int,
        onSave: (String) -> Boolean,
    ) {
        val en = ShopSettings.language(this) == AppLanguage.EN
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            this.inputType = inputType
            setTextColor(ink)
            typeface = Type.body(this@SettingsActivity)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Theme.subtle)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), line)
            }
        }
        val holder = android.widget.FrameLayout(this).apply {
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(holder)
            .setPositiveButton(if (en) "Save" else "सेव करें", null)
            .setNegativeButton(if (en) "Cancel" else "रहने दें", null)
            .create()
        dialog.setOnShowListener {
            // Validated before closing, so a mistyped UPI ID gets a second
            // chance instead of being silently thrown away.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (onSave(input.text.toString().trim())) {
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun editShopName() {
        val en = ShopSettings.language(this) == AppLanguage.EN
        textDialog(
            if (en) "Shop name" else "दुकान का नाम",
            if (en) "Shown on every page and in every reminder your customers read."
            else "हर पेज पर और हर याद-संदेश में यही नाम दिखेगा।",
            ShopSettings.name(this),
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        ) { value ->
            if (value.isBlank()) false else {
                ShopSettings.setName(this, value)
                scope.launch { ShopSettings.persist(this@SettingsActivity) }
                true
            }
        }
    }

    private fun editUpiId() {
        val en = ShopSettings.language(this) == AppLanguage.EN
        textDialog(
            "UPI ID",
            if (en) "Example: yourshop@okaxis\nReminders will carry a link your customer taps to pay."
            else "उदाहरण: yourshop@okaxis\nयाद-संदेश में लिंक जाएगा, ग्राहक टैप करके भुगतान कर सकेगा।",
            ShopSettings.upiId(this).orEmpty(),
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        ) { value ->
            when {
                value.isBlank() -> { ShopSettings.setUpiId(this, null); true }
                ShopSettings.isValidUpiId(value) -> { ShopSettings.setUpiId(this, value); true }
                else -> {
                    Toast.makeText(
                        this,
                        if (en) "That doesn't look like a UPI ID (name@bank)" else "यह UPI ID सही नहीं लग रही (नाम@बैंक)",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        }
    }

    /**
     * The price list, as one box of lines. A shopkeeper already keeps this
     * list in a notebook; copying it line for line is faster than any form.
     */
    private fun editCatalogue() {
        val en = ShopSettings.language(this) == AppLanguage.EN
        val input = EditText(this).apply {
            setText(app.vaaniledger.data.Catalogue.format(app.vaaniledger.data.Catalogue.items(this@SettingsActivity)))
            hint = if (en) "muslin kurti 450\nsugar 45/kg\ndolo 650, 30" else "मलमल कुर्ती 450\nचीनी 45/kg\nडोलो 650, 30"
            minLines = 8
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(ink)
            typeface = Type.body(this@SettingsActivity)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Theme.subtle)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), line)
            }
        }
        val holder = android.widget.FrameLayout(this).apply {
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (en) "Items & prices" else "सामान और दाम")
            .setMessage(
                if (en) "One item per line, name then price. Vaani will recognise these names in chats — even unusual ones — and fill the bill when a customer doesn't say the total."
                else "हर लाइन में एक चीज़: नाम, फिर दाम। Vaani चैट में ये नाम पहचानेगी — अनोखे नाम भी — और ग्राहक कुल न बताए तो बिल ख़ुद भरेगी।"
            )
            .setView(holder)
            .setPositiveButton(if (en) "Save" else "सेव करें") { _, _ ->
                val items = app.vaaniledger.data.Catalogue.parse(input.text.toString())
                app.vaaniledger.data.Catalogue.save(this, items)
                app.vaaniledger.sync.SyncWorker.syncNow(this, ShopSettings.ID, ShopSettings.name(this))
                Toast.makeText(
                    this, if (en) "${items.size} items saved" else "${items.size} चीज़ें सेव हुईं", Toast.LENGTH_SHORT,
                ).show()
                render()
            }
            .setNegativeButton(if (en) "Cancel" else "रहने दें", null)
            .show()
    }

    /** Honest limits, so a missed order is a known case rather than a mystery. */
    private fun showCapabilities() {
        val en = ShopSettings.language(this) == AppLanguage.EN
        val body = if (en) """
            READS WELL
            • Orders spread over several messages — "2 kurti", then "blue wali", then "kitna hua?"
            • Hindi, English, Hinglish and Devanagari, number words too ("dhai sau", "ढाई सौ")
            • Payments: "paid 500", "UPI kar diya", "kal dunga"
            • Prices you quoted in your own replies
            • Any item on your price list, even one letter misspelt
            • Several days of chat at once — each day is its own order
            • Skips what was already booked, your own reminders, and date lines

            CAN'T READ
            • Photos, voice notes, stickers or PDFs of a bill — enter those by hand or use the mic
            • Messages scrolled off screen — scroll up and tap again to read more
            • Group chats with several customers
            • A total nobody said and an item not on your price list

            TIP: put your items and prices in Settings → Items & prices.
        """.trimIndent() else """
            अच्छे से पढ़ती है
            • कई संदेशों में बँटा ऑर्डर — "2 कुर्ती", फिर "नीली वाली", फिर "कितना हुआ?"
            • हिंदी, अंग्रेज़ी, हिंग्लिश और देवनागरी, शब्दों में गिनती भी ("ढाई सौ")
            • भुगतान: "500 दे दिए", "UPI कर दिया", "कल दूँगा"
            • आपके जवाब में बताए दाम
            • आपकी सूची का हर सामान, एक अक्षर ग़लत हो तो भी
            • कई दिनों की चैट एक साथ — हर दिन का अलग ऑर्डर
            • जो पहले लिखा जा चुका, आपके याद-संदेश और तारीख़ की लाइनें छोड़ देती है

            नहीं पढ़ पाती
            • फ़ोटो, वॉइस नोट, स्टिकर या बिल की PDF — ये हाथ से या माइक से डालिए
            • स्क्रीन से ऊपर निकल चुके संदेश — ऊपर स्क्रॉल करके फिर टैप करें
            • कई ग्राहकों वाले ग्रुप
            • जिसका कुल किसी ने नहीं बताया और जो सामान सूची में नहीं

            सुझाव: सेटिंग्स → सामान और दाम में अपनी सूची डालिए।
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle(if (en) "What Vaani can read" else "Vaani क्या पढ़ सकती है")
            .setMessage(body)
            .setPositiveButton(if (en) "Got it" else "ठीक है", null)
            .setNeutralButton(if (en) "Add prices" else "दाम डालें") { _, _ -> editCatalogue() }
            .show()
    }

    /**
     * Pair with the dashboard: the computer shows a code, the phone types it.
     * The phone already knows the server, so the code is all it needs -- and
     * the address is offered here too, since pairing is when it gets set.
     */
    private fun pairComputer() {
        val en = ShopSettings.language(this) == AppLanguage.EN
        fun field(value: String, hintText: String, type: Int) = EditText(this).apply {
            setText(value)
            hint = hintText
            inputType = type
            setTextColor(ink)
            typeface = Type.body(this@SettingsActivity)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Theme.subtle)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), line)
            }
        }
        val code = field("", if (en) "6-digit code" else "6 अंकों का कोड", android.text.InputType.TYPE_CLASS_NUMBER).apply {
            textSize = 24f
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        val address = field(
            ServerSettings.baseUrl(this), "http://192.168.1.42:8000/",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI,
        )
        // The phone looks for the computer itself; the address box is only a
        // fallback, folded away until someone asks for it.
        val found = text(if (en) "Looking for your computer on this Wi-Fi…" else "इस Wi-Fi पर आपका कंप्यूटर ढूँढ रहे हैं…", 13f, muted).apply {
            setPadding(0, dp(14), 0, dp(4))
        }
        val manual = text(if (en) "Enter the address myself" else "पता ख़ुद डालें", 13f, Theme.primary, Type.medium(this)).apply {
            setPadding(0, dp(8), 0, dp(6))
        }
        address.visibility = View.GONE
        manual.setOnClickListener { address.visibility = View.VISIBLE; manual.visibility = View.GONE }
        val finder = app.vaaniledger.net.ServerFinder(this) { url, name ->
            address.setText(url)
            found.text = if (en) "✓ Found $name on this Wi-Fi" else "✓ इस Wi-Fi पर $name मिल गया"
            found.setTextColor(Theme.good)
        }
        // Not found in a few seconds: say so, and open the address box.
        found.postDelayed({
            if (found.text.startsWith("✓")) return@postDelayed
            found.text = if (en) "Couldn't find it. Is the computer on the same Wi-Fi? You can type the address shown on the dashboard."
            else "नहीं मिला। क्या कंप्यूटर इसी Wi-Fi पर है? डैशबोर्ड पर लिखा पता डाल सकते हैं।"
            address.visibility = View.VISIBLE
            manual.visibility = View.GONE
        }, 6000)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(code)
            addView(found)
            addView(manual)
            addView(address)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (en) "Connect a computer" else "कंप्यूटर से जोड़ें")
            .setMessage(
                if (en) "On the computer, open http://vaani.local:8000 and press Connect phone. Type the code it shows. Phone and computer must be on the same Wi-Fi."
                else "कंप्यूटर पर http://vaani.local:8000 खोलिए और Connect phone दबाइए। जो कोड दिखे, यहाँ लिखिए। फ़ोन और कंप्यूटर एक ही Wi-Fi पर हों।"
            )
            .setView(holder)
            .setPositiveButton(if (en) "Connect" else "जोड़ें", null)
            .setNegativeButton(if (en) "Cancel" else "रहने दें", null)
            .create()
        dialog.setOnDismissListener { finder.stop() }
        finder.start()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val digits = code.text.toString().trim()
                if (digits.length != 6) { code.error = if (en) "6 digits" else "6 अंक"; return@setOnClickListener }
                address.text.toString().trim().takeIf { it.isNotBlank() }?.let { ServerSettings.setBaseUrl(this, it) }
                button.isEnabled = false
                scope.launch {
                    val result = runCatching {
                        app.vaaniledger.net.ApiClient.api(this@SettingsActivity).pairClaim(
                            app.vaaniledger.net.PairClaim(
                                code = digits,
                                shopId = ShopSettings.ID,
                                shopName = ShopSettings.name(this@SettingsActivity),
                                shopCategory = ShopSettings.category(this@SettingsActivity).wire,
                            )
                        )
                    }
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        button.isEnabled = true
                        val ok = result.getOrNull()
                        when {
                            ok?.ok == true -> {
                                dialog.dismiss()
                                // Everything on the phone goes up now, so the
                                // dashboard opens on this shop's real khata.
                                app.vaaniledger.sync.SyncWorker.syncFull(this@SettingsActivity, ShopSettings.ID, ShopSettings.name(this@SettingsActivity))
                                Toast.makeText(this@SettingsActivity,
                                    if (en) "Connected — the dashboard is opening your khata" else "जुड़ गया — डैशबोर्ड पर आपका खाता खुल रहा है",
                                    Toast.LENGTH_LONG).show()
                                render()
                            }
                            ok != null -> code.error = ok.message ?: (if (en) "Wrong or expired code" else "कोड ग़लत है या पुराना हो गया")
                            else -> Toast.makeText(this@SettingsActivity,
                                if (en) "Can't reach the computer — check the address and the Wi-Fi" else "कंप्यूटर तक नहीं पहुँच पाए — पता और Wi-Fi जाँचिए",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * The code that opens this shop's khata on the hosted dashboard.
     *
     * Made here, registered with the server, then shown big enough to read
     * out. It works until a new one is made, which signs every browser that
     * used the old one out -- the answer to "I gave the code to someone I
     * shouldn't have".
     */
    private fun dashboardCode() {
        val en = ShopSettings.language(this) == AppLanguage.EN
        val code = app.vaaniledger.data.DashboardAccess.code(this)
        val site = ServerSettings.baseUrl(this).trimEnd('/')
        if (code == null) {
            AlertDialog.Builder(this)
                .setTitle(if (en) "Dashboard code" else "डैशबोर्ड कोड")
                .setMessage(
                    if (en) "Make a code, then type it on the website ($site) to see this khata from any computer. Anyone with the code can see and add entries, so share it only with people you trust."
                    else "कोड बनाइए, फिर वेबसाइट ($site) पर डालकर किसी भी कंप्यूटर से यह खाता देखिए। कोड जिसके पास होगा वह खाता देख और एंट्री जोड़ सकता है — भरोसेमंद लोगों को ही दीजिए।"
                )
                .setPositiveButton(if (en) "Make code" else "कोड बनाइए") { _, _ -> registerDashboardCode(app.vaaniledger.data.DashboardAccess.newCode()) }
                .setNegativeButton(if (en) "Cancel" else "रहने दें", null)
                .show()
            return
        }
        val big = text(code, 34f, Theme.primary, Type.display(this)).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(4))
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(big)
            addView(text(site, 14f, Theme.muted).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) })
        }
        AlertDialog.Builder(this)
            .setTitle(if (en) "Dashboard code" else "डैशबोर्ड कोड")
            .setMessage(
                if (en) "Open the website on any computer and type this code. It keeps working until you make a new one."
                else "किसी भी कंप्यूटर पर वेबसाइट खोलिए और यह कोड डालिए। नया कोड बनाने तक यही चलेगा।"
            )
            .setView(holder)
            .setPositiveButton(if (en) "Share" else "भेजें") { _, _ ->
                val body = if (en) "Vaani khata of ${ShopSettings.name(this)}:\nOpen $site and type the code $code"
                else "${ShopSettings.name(this)} का Vaani खाता:\n$site खोलिए और कोड $code डालिए"
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body),
                    if (en) "Send the dashboard code" else "डैशबोर्ड कोड भेजें",
                ))
            }
            .setNeutralButton(if (en) "New code" else "नया कोड") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(if (en) "Make a new code?" else "नया कोड बनाएँ?")
                    .setMessage(
                        if (en) "Everyone using $code on the website is signed out. Use this if the code reached the wrong person."
                        else "वेबसाइट पर $code से खुला खाता सबके लिए बंद हो जाएगा। कोड ग़लत हाथ में गया हो तो यही कीजिए।"
                    )
                    .setPositiveButton(if (en) "New code" else "नया कोड") { _, _ -> registerDashboardCode(app.vaaniledger.data.DashboardAccess.newCode()) }
                    .setNeutralButton(if (en) "Turn off" else "बंद करें") { _, _ -> registerDashboardCode(null) }
                    .setNegativeButton(if (en) "Cancel" else "रहने दें", null)
                    .show()
            }
            .setNegativeButton(if (en) "Close" else "बंद करें", null)
            .show()
    }

    /** Tell the server first; keep the code only once it has it, so the screen never shows a code that won't work. */
    private fun registerDashboardCode(code: String?) {
        val en = ShopSettings.language(this) == AppLanguage.EN
        val site = ServerSettings.baseUrl(this)
        scope.launch {
            // A free hosted server sleeps when idle and takes up to a minute
            // to wake, far longer than the app's short timeouts. Keep asking
            // for about 90 seconds -- the first request is what wakes it --
            // and say so, instead of failing on the first try.
            var result: app.vaaniledger.net.DashboardCodeResult? = null
            var lastError: Throwable? = null
            val until = System.currentTimeMillis() + 90_000
            var attempt = 0
            while (result == null && System.currentTimeMillis() < until) {
                attempt++
                runCatching {
                    app.vaaniledger.net.ApiClient.api(this@SettingsActivity).setDashboardCode(
                        ShopSettings.ID, app.vaaniledger.net.DashboardCodeRequest(code ?: ""),
                    )
                }.onSuccess { result = it }.onFailure { e ->
                    lastError = e
                    android.util.Log.w("VaaniSettings", "dashboard code attempt $attempt failed: ${e.message}")
                    // A refusal from the server is an answer, not a sleeping host.
                    if (e is retrofit2.HttpException) return@onFailure
                    if (attempt == 1) kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SettingsActivity,
                            if (en) "Waking up the dashboard server — this can take a minute…"
                            else "डैशबोर्ड सर्वर जाग रहा है — एक मिनट लग सकता है…",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    kotlinx.coroutines.delay(4_000)
                }
                if (lastError is retrofit2.HttpException) break
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (result?.ok != true) {
                    val refused = (lastError as? retrofit2.HttpException)?.code()
                    Toast.makeText(
                        this@SettingsActivity,
                        when {
                            refused == 401 -> if (en) "The server knows this shop from another phone. Only that phone can make its code."
                                else "यह दुकान सर्वर पर किसी दूसरे फ़ोन से जुड़ी है। कोड वही फ़ोन बना सकता है।"
                            refused != null -> if (en) "The server said no ($refused). Check Settings → Server address: $site"
                                else "सर्वर ने मना किया ($refused)। सेटिंग्स → सर्वर का पता जाँचिए: $site"
                            else -> if (en) "Couldn't reach $site — check the internet and Settings → Server address"
                                else "$site तक नहीं पहुँच पाए — इंटरनेट और सेटिंग्स → सर्वर का पता जाँचिए"
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                    return@withContext
                }
                app.vaaniledger.data.DashboardAccess.setCode(this@SettingsActivity, code)
                // The khata goes up now, so the website opens on real entries.
                app.vaaniledger.sync.SyncWorker.syncFull(this@SettingsActivity, ShopSettings.ID, ShopSettings.name(this@SettingsActivity))
                render()
                if (code != null) dashboardCode()
                else Toast.makeText(this@SettingsActivity, if (en) "Online dashboard turned off" else "ऑनलाइन डैशबोर्ड बंद", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Send someone the dashboard address. The easy name first; the IP the
     * phone is connected to as well, for a computer whose browser cannot
     * open ".local" names (some Android and older Windows ones).
     */
    private fun shareDashboard() {
        val en = ShopSettings.language(this) == AppLanguage.EN
        val ip = ServerSettings.baseUrl(this).takeIf { ServerSettings.isCustom(this) }
        val body = buildString {
            append(if (en) "Vaani dashboard for ${ShopSettings.name(this@SettingsActivity)}:\n" else "${ShopSettings.name(this@SettingsActivity)} का Vaani डैशबोर्ड:\n")
            append("http://vaani.local:8000\n")
            if (ip != null) append(if (en) "If that doesn't open: $ip\n" else "न खुले तो: $ip\n")
            append(if (en) "Open it on a computer on the shop's Wi-Fi, then press Connect phone."
            else "दुकान के Wi-Fi वाले कंप्यूटर पर खोलिए, फिर Connect phone दबाइए।")
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body),
            if (en) "Send the dashboard address" else "डैशबोर्ड का पता भेजें",
        ))
    }

    private fun exportKhata() {
        val en = ShopSettings.language(this) == AppLanguage.EN
        scope.launch {
            val intent = runCatching {
                app.vaaniledger.data.KhataExport.build(
                    this@SettingsActivity, ShopSettings.ID, ShopSettings.name(this@SettingsActivity),
                )
            }.onFailure { android.util.Log.e("VaaniSettings", "export failed", it) }.getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (intent == null) {
                    Toast.makeText(
                        this@SettingsActivity,
                        if (en) "Couldn't create the file" else "फ़ाइल नहीं बन पाई",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    startActivity(Intent.createChooser(intent, if (en) "Send the khata to…" else "खाता कहाँ भेजें?"))
                }
            }
        }
    }

    /**
     * Only screen in the app that asks for a URL. Meant for setup, not for a
     * shopkeeper's daily use -- but a real phone cannot reach the emulator's
     * loopback alias, and rebuilding the APK every time a Wi-Fi network
     * changes is not something a phone can do to itself.
     */
    private fun editServerAddress() {
        val lang = ShopSettings.language(this)
        val input = EditText(this).apply {
            setText(ServerSettings.baseUrl(this@SettingsActivity))
            setSelection(text.length)
            setTextColor(ink)
            typeface = Type.body(this@SettingsActivity)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Theme.subtle)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), line)
            }
        }
        val holder = android.widget.FrameLayout(this).apply {
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (lang == AppLanguage.EN) "Server address" else "सर्वर का पता")
            .setMessage(
                if (lang == AppLanguage.EN)
                    "Example: http://192.168.1.42:8000/\nMust be on the same Wi-Fi as this phone."
                else
                    "उदाहरण: http://192.168.1.42:8000/\nफ़ोन और सर्वर एक ही Wi-Fi पर होने चाहिए।"
            )
            .setView(holder)
            .setPositiveButton(if (lang == AppLanguage.EN) "Save" else "सेव करें") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    ServerSettings.setBaseUrl(this, value)
                    Toast.makeText(this, if (lang == AppLanguage.EN) "Saved" else "सेव हो गया", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .setNeutralButton(if (lang == AppLanguage.EN) "Use default" else "पहले वाला पता") { _, _ ->
                ServerSettings.reset(this)
                render()
            }
            .setNegativeButton(if (lang == AppLanguage.EN) "Cancel" else "रहने दें", null)
            .show()
    }

}
