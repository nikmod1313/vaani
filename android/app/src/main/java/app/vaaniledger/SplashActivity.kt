package app.vaaniledger

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.ui.Art
import app.vaaniledger.ui.ArtView
import app.vaaniledger.ui.Motion
import app.vaaniledger.ui.OrnamentRule
import app.vaaniledger.ui.ScriptLineArtView
import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.dp
import app.vaaniledger.ui.Views.text
import app.vaaniledger.ui.drawCover

/**
 * The opening: वाणी written out in gold over the indigo palace.
 *
 * Three seconds, once per launch, and a tap anywhere skips straight to the
 * finished name and on into the app -- a shopkeeper opening this between
 * customers should never be made to wait for a flourish.
 *
 * Routing is unchanged: this always hands to [MainActivity], which still
 * decides between onboarding and home.
 */
class SplashActivity : Activity() {

    private lateinit var script: ScriptLineArtView
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw under the status and navigation bars: the palace runs to the
        // very bottom of the glass.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val t = Strings.of(ShopSettings.language(this)).main

        val root = FrameLayout(this).apply { setBackgroundColor(Theme.primaryDeep) }

        // The palace, slowly settling into place.
        val palace = PalaceBackdrop(this)
        root.addView(palace, FrameLayout.LayoutParams(-1, -1))

        // The sun, as a faint halo behind the word, turning very slowly.
        // Screen-blended, so on indigo it reads as light rather than as a
        // dimmed photograph of a gold object.
        val sun = ArtView(this, R.drawable.art_sun, alignBottom = false).apply {
            alpha = 0f
            setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
            })
        }
        val sunSize = (resources.displayMetrics.widthPixels * 1.05f).toInt()
        root.addView(sun, FrameLayout.LayoutParams(sunSize, sunSize, Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (resources.displayMetrics.heightPixels * 0.075f).toInt()
        })

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        script = ScriptLineArtView(this)
        column.addView(script, LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.72f).toInt(),
            (resources.displayMetrics.widthPixels * 0.72f * 1.1f).toInt(),
        ))

        val wordmark = text("VAANI", 22f, Theme.goldPale, Type.display(this)).apply {
            letterSpacing = 0.55f
            gravity = Gravity.CENTER
            alpha = 0f
            setPadding(0, dp(18), 0, dp(6))
        }
        val rule = OrnamentRule(this, flourish = true, colour = Theme.gold).apply { alpha = 0f }
        val tagline = text(t.tagline, 15f, Theme.onPrimaryMuted, Type.displayItalic(this)).apply {
            alpha = 0f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        column.addView(wordmark, LinearLayout.LayoutParams(-1, -2))
        column.addView(rule, LinearLayout.LayoutParams(dp(150), dp(12)))
        column.addView(tagline, LinearLayout.LayoutParams(-1, -2))

        root.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = (resources.displayMetrics.heightPixels * 0.09f).toInt()
        })

        root.setOnClickListener { skip() }
        setContentView(root)

        // --- the sequence ---------------------------------------------------
        palace.alpha = 0f
        palace.scaleX = 1.08f; palace.scaleY = 1.08f
        palace.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(3400).setInterpolator(Motion.ease).start()

        sun.scaleX = 0.7f; sun.scaleY = 0.7f
        sun.animate().alpha(0.26f).scaleX(1f).scaleY(1f).setStartDelay(300)
            .setDuration(2600).setInterpolator(Motion.ease).start()
        android.animation.ObjectAnimator.ofFloat(sun, View.ROTATION, 0f, 30f).apply {
            duration = 12000
            interpolator = LinearInterpolator()
            start()
        }

        listOf(wordmark, rule, tagline).forEachIndexed { i, v ->
            v.translationY = dp(8).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(2100L + i * 160).setDuration(600).setInterpolator(Motion.ease).start()
        }

        script.onFinished = {
            chime()
            root.postDelayed({ proceed() }, 650)
        }
        script.postDelayed({ script.play() }, if (savedInstanceState == null) 250 else 0)
    }

    /**
     * The chime that closes the opening: a santoor-like Sa-Ga-Pa-Sa' with a
     * small temple bell on the last note, composed for Vaani (res/raw).
     *
     * Played on the application context so it rings on into the home screen
     * instead of being cut off when this activity finishes. Silent when the
     * phone is on silent or vibrate -- an app that chimes in a quiet shop,
     * or a meeting, gets its sound turned off for good -- and silent when
     * the writing is skipped, since a tap means "get on with it".
     */
    private fun chime() {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        if (audio.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
            val player = android.media.MediaPlayer()
            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            applicationContext.resources.openRawResourceFd(R.raw.vaani_chime).use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.setOnCompletionListener { it.release() }
            player.prepare()
            player.start()
        }.onFailure { android.util.Log.w("VaaniSplash", "chime failed", it) }
    }

    private fun skip() {
        script.finishNow()
        proceed()
    }

    private fun proceed() {
        if (leaving || isFinishing) return
        leaving = true
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.vaani_fade_in, R.anim.vaani_fade_out)
        finish()
    }

    /**
     * The palace photograph at full width, standing on the bottom edge, with
     * its own night sky continued above it and an indigo wash deepening
     * toward the top so the gold writing sits on quiet sky. Fitting the width
     * rather than cropping to cover keeps the palace low on a tall phone,
     * clear of the name.
     */
    private class PalaceBackdrop(activity: Activity) : View(activity) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val wash = Paint()

        /** The photograph's own sky, sampled from its top edge. */
        private val SKY = Color.rgb(0x06, 0x1F, 0x57)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val deep = Theme.primaryDeep
            wash.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    Color.argb(0xD0, Color.red(deep), Color.green(deep), Color.blue(deep)),
                    Color.argb(0x50, Color.red(deep), Color.green(deep), Color.blue(deep)),
                    Color.argb(0x10, Color.red(deep), Color.green(deep), Color.blue(deep)),
                    Color.argb(0x70, Color.red(deep), Color.green(deep), Color.blue(deep)),
                ),
                floatArrayOf(0f, 0.45f, 0.75f, 1f), Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val bmp = Art.get(context, R.drawable.art_palace)
            val h = bmp.height * (width.toFloat() / bmp.width)
            canvas.drawColor(SKY)
            if (h >= height) {
                canvas.drawCover(bmp, r, paint, focusY = 1f)
            } else {
                canvas.drawBitmap(bmp, null, RectF(0f, height - h, width.toFloat(), height.toFloat()), paint)
                // Feather the photograph's top edge into the plain sky above it.
                val seam = Paint().apply {
                    shader = LinearGradient(0f, height - h, 0f, height - h + h * 0.18f, SKY, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, height - h, width.toFloat(), height - h + h * 0.18f, seam)
            }
            canvas.drawRect(r, wash)
        }
    }
}
