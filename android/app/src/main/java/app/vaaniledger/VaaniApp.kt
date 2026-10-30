package app.vaaniledger

import android.app.Application

/**
 * Application entry point. Deliberately thin -- the database is created
 * lazily by LedgerDatabase.get(), so nothing here blocks a cold start.
 */
class VaaniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // One small preferences read: the shop's own item names, so the very
        // first capture after a restart already knows them.
        app.vaaniledger.data.Catalogue.install(this)
        // The background safety net. Written but never started until now, so
        // nothing reached the dashboard unless an entry was made.
        app.vaaniledger.sync.SyncWorker.schedulePeriodic(this, app.vaaniledger.data.ShopSettings.ID)
    }
}
