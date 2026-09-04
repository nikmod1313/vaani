package app.vaaniledger

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

    private val ink = Color.rgb(0x17, 0x19, 0x1E)
    private val muted = Color.rgb(0x7A, 0x7E, 0x88)
    private val indigo = Color.rgb(0x25, 0x3A, 0x6E)
    private val paper = Color.rgb(0xF7, 0xF6, 0xF3)
    private val line = Color.rgb(0xDF, 0xDC, 0xD5)

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            setPadding(dp(22), dp(36), dp(22), dp(28))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        root.removeAllViews()
        val lang = ShopSettings.language(this)
        val t = Strings.of(lang).main

        root.addView(TextView(this).apply {
            text = t.settingsTitle
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(22))
        })

        root.addView(settingRow(
            "🛒", if (lang == AppLanguage.EN) "Shop category" else "Dukaan ki category",
            ShopSettings.category(this).label,
        ) { chooseCategory() })

        root.addView(settingRow(
            "🌐", if (lang == AppLanguage.EN) "Language" else "Bhasha",
            lang.displayName,
        ) { chooseLanguage() })

        root.addView(TextView(this).apply {
            text = if (lang == AppLanguage.EN) "ADVANCED" else "ADVANCED"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(24), dp(4), dp(8))
        })

        root.addView(settingRow(
            "🖥️", if (lang == AppLanguage.EN) "Server address" else "Server ka address",
            ServerSettings.baseUrl(this) +
                if (ServerSettings.isCustom(this)) "" else
                    (if (lang == AppLanguage.EN) "  (default)" else "  (default)"),
        ) { editServerAddress() })

        root.addView(TextView(this).apply {
            text = "← " + (if (lang == AppLanguage.EN) "Back" else "Peeche")
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(26), dp(4), dp(4))
            isClickable = true
            setOnClickListener { finish() }
        })
    }

    private fun settingRow(icon: String, title: String, value: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), line)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }

            addView(TextView(this@SettingsActivity).apply {
                text = icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(0, 0, dp(14), 0)
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = title
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = value
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply {
                text = "›"
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            })
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
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(if (lang == AppLanguage.EN) "Server address" else "Server ka address")
            .setMessage(
                if (lang == AppLanguage.EN)
                    "Example: http://192.168.1.42:8000/\nMust be on the same Wi-Fi as this phone."
                else
                    "Udaharan: http://192.168.1.42:8000/\nPhone aur server ek hi Wi-Fi par hone chahiye."
            )
            .setView(input)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
