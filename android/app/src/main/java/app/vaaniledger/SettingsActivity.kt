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

        root.addView(sectionHeading(if (en) "Your shop" else "Aapki dukaan"))
        root.addView(group(
            settingRow(
                LineIcon.Glyph.PENCIL, if (en) "Shop name" else "Dukaan ka naam",
                ShopSettings.name(this),
            ) { editShopName() },
            settingRow(
                LineIcon.Glyph.QR, "UPI ID",
                ShopSettings.upiId(this) ?: if (en) "Not set — reminders go without a pay link"
                else "Nahi daala — reminder mein pay link nahi jayega",
            ) { editUpiId() },
            settingRow(
                LineIcon.Glyph.SHOP, if (en) "Shop category" else "Dukaan ki category",
                ShopSettings.category(this).label,
            ) { chooseCategory() },
            settingRow(
                LineIcon.Glyph.GLOBE, if (en) "Language" else "Bhasha",
                lang.displayName,
            ) { chooseLanguage() },
        ))

        root.addView(sectionHeading(if (en) "Backup" else "Khata ka backup"))
        root.addView(group(
            settingRow(
                LineIcon.Glyph.SHARE, if (en) "Export the khata" else "Khata export karein",
                if (en) "Every entry in one spreadsheet — send it to WhatsApp, Drive or email"
                else "Saari entries ek file mein — WhatsApp, Drive ya email par bhejein",
            ) { exportKhata() },
        ))

        root.addView(sectionHeading("Advanced"))
        root.addView(group(
            settingRow(
                LineIcon.Glyph.SERVER, if (en) "Server address" else "Server ka address",
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
        val labels = options.map { it.label }.toTypedArray()
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
            .setPositiveButton(if (en) "Save" else "Save karein", null)
            .setNegativeButton(if (en) "Cancel" else "Rehne dein", null)
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
            if (en) "Shop name" else "Dukaan ka naam",
            if (en) "Shown on every page and in every reminder your customers read."
            else "Har page par aur har reminder mein yahi naam dikhega.",
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
            if (en) "Example: sharmakirana@okaxis\nReminders will carry a link your customer taps to pay."
            else "Udaharan: sharmakirana@okaxis\nReminder mein link jayega, grahak tap karke pay kar sakega.",
            ShopSettings.upiId(this).orEmpty(),
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        ) { value ->
            when {
                value.isBlank() -> { ShopSettings.setUpiId(this, null); true }
                ShopSettings.isValidUpiId(value) -> { ShopSettings.setUpiId(this, value); true }
                else -> {
                    Toast.makeText(
                        this,
                        if (en) "That doesn't look like a UPI ID (name@bank)" else "Yeh UPI ID sahi nahi lag raha (naam@bank)",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        }
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
                        if (en) "Couldn't create the file" else "File nahi ban payi",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    startActivity(Intent.createChooser(intent, if (en) "Send the khata to…" else "Khata kahan bhejein?"))
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
            .setTitle(if (lang == AppLanguage.EN) "Server address" else "Server ka address")
            .setMessage(
                if (lang == AppLanguage.EN)
                    "Example: http://192.168.1.42:8000/\nMust be on the same Wi-Fi as this phone."
                else
                    "Udaharan: http://192.168.1.42:8000/\nPhone aur server ek hi Wi-Fi par hone chahiye."
            )
            .setView(holder)
            .setPositiveButton(if (lang == AppLanguage.EN) "Save" else "Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    ServerSettings.setBaseUrl(this, value)
                    Toast.makeText(this, if (lang == AppLanguage.EN) "Saved" else "Save ho gaya", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .setNeutralButton(if (lang == AppLanguage.EN) "Use default" else "Default use karein") { _, _ ->
                ServerSettings.reset(this)
                render()
            }
            .setNegativeButton(if (lang == AppLanguage.EN) "Cancel" else "Cancel", null)
            .show()
    }

}
