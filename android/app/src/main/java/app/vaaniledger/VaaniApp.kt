package app.vaaniledger

import android.app.Application

/**
 * Application entry point. Deliberately thin -- the database is created
 * lazily by LedgerDatabase.get(), so nothing here blocks a cold start.
 */
class VaaniApp : Application()
