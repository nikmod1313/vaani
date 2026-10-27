package app.vaaniledger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import java.util.Locale

/**
 * Voice entry — "Bol ke likho".
 *
 * A transparent Activity rather than something the overlay service does
 * itself, for two reasons a Service cannot work around: RECORD_AUDIO needs a
 * runtime permission request, and only an Activity can ask; and
 * RecognizerIntent returns its result to an Activity.
 *
 * Uses Android's own recogniser, which runs on-device for hi-IN on most
 * phones — so voice entry keeps working with no network, same as the rest of
 * the capture paths.
 */
class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        listen()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            toast(strings.micNeeded)
            finish()
        }
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // Hindi first; the recogniser still returns Hinglish, which is
            // what the extraction engine is built for.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, strings.voicePrompt)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { startActivityForResult(intent, REQ_SPEECH) }
            .onFailure {
                toast(strings.voiceUnavailable)
                finish()
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_SPEECH) return finish()

        val spoken = data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()

        if (resultCode != RESULT_OK || spoken.isNullOrBlank()) {
            toast(strings.heardNothing)
        } else {
            // Same pipeline as chat capture: extract, confirm, save. Voice is
            // just another way in, not a separate path.
            startService(
                Intent(this, app.vaaniledger.overlay.OverlayService::class.java).apply {
                    action = app.vaaniledger.overlay.OverlayService.ACTION_EXTRACT_TEXT
                    putExtra(app.vaaniledger.overlay.OverlayService.EXTRA_TEXT, spoken)
                }
            )
        }
        finish()
    }

    private val strings get() = app.vaaniledger.data.Strings.of(
        app.vaaniledger.data.ShopSettings.language(this),
    ).sheets

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_MIC = 11
        private const val REQ_SPEECH = 12
    }
}
