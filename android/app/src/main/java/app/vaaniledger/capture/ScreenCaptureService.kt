package app.vaaniledger.capture

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Screenshot capture for the OCR fallback path.
 *
 * Declared now because holding ONE long-lived MediaProjection is what keeps
 * Android 14+ from re-prompting for consent on every capture -- that decision
 * shapes the service's lifetime, so it belongs in the manifest from the
 * start rather than being retrofitted.
 *
 * TODO(week 3): ImageReader -> Bitmap -> ML Kit (Devanagari + Latin).
 */
class ScreenCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
