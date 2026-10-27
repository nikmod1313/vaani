package app.vaaniledger.ui

import android.app.Activity
import app.vaaniledger.AnalyticsActivity
import app.vaaniledger.LedgerActivity
import app.vaaniledger.MainActivity
import app.vaaniledger.SettingsActivity
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.ui.Scaffold.navigateTo

/**
 * The app's four destinations, in one place.
 *
 * Every page builds its bar from this list, so the order and the labels
 * cannot differ between screens -- a shopkeeper who has learned that the
 * report is second should never find it third.
 */
object Nav {

    fun items(activity: Activity): List<BottomNav.Item> {
        val t = Strings.of(ShopSettings.language(activity)).nav
        return listOf(
            BottomNav.Item(BottomNav.Destination.HOME, LineIcon.Glyph.HOME, t.home),
            BottomNav.Item(BottomNav.Destination.REPORT, LineIcon.Glyph.REPORT, t.report),
            BottomNav.Item(BottomNav.Destination.LEDGER, LineIcon.Glyph.LEDGER, t.ledger),
            BottomNav.Item(BottomNav.Destination.SETTINGS, LineIcon.Glyph.SETTINGS, t.settings),
        )
    }

    fun go(activity: Activity, destination: BottomNav.Destination) {
        val target = when (destination) {
            BottomNav.Destination.HOME -> MainActivity::class.java
            BottomNav.Destination.REPORT -> AnalyticsActivity::class.java
            BottomNav.Destination.LEDGER -> LedgerActivity::class.java
            BottomNav.Destination.SETTINGS -> SettingsActivity::class.java
        }
        activity.navigateTo(target)
    }
}
