package dev.twoperson.messenger

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import dev.twoperson.messenger.data.Db
import dev.twoperson.messenger.data.MessageRepository
import dev.twoperson.messenger.data.SecureStore
import dev.twoperson.messenger.data.SettingsStore
import dev.twoperson.messenger.engine.ConnectionEngine
import dev.twoperson.messenger.service.IncomingNotifier
import dev.twoperson.messenger.service.MessageNotifications
import dev.twoperson.messenger.util.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Single application-scoped container. Everything here is device local: no analytics,
 * no crash reporting, no network client that talks to a backend.
 */
class AppContainer(context: Context, val settings: SettingsStore) {
    val database: Db = Db(context)
    val repository: MessageRepository = MessageRepository(database)
    val secureStore: SecureStore = SecureStore(context)
    val notifier: IncomingNotifier = IncomingNotifier(context, repository, settings)
    val engine: ConnectionEngine = ConnectionEngine(
        context,
        secureStore,
        repository,
        onIncomingMessage = { message -> notifier.onIncoming(message) }
    )

    init {
        // The first screen query needs the contact list and the selected conversation. The
        // database is tiny and this runs before any activity is created, which avoids a race
        // between the engine reading an empty list and the repository finishing its first load.
        runBlocking(Dispatchers.IO) {
            repository.initialize()
            migrateLegacyInvitation()
        }
    }

    /**
     * Version 1 kept exactly one invitation under a fixed SecureStore key and one pinned peer.
     * Version 4 moves both onto the legacy contact row so the upgraded app keeps its history
     * and its pairing. The old database peer table is intentionally left untouched.
     */
    private suspend fun migrateLegacyInvitation() {
        val legacySecret = secureStore.legacyInviteSecret() ?: return
        val existing = repository.contacts.value.firstOrNull { it.id == Db.LEGACY_CONTACT_ID }
        val contactId = existing?.id ?: repository.ensureLegacyContact()
        secureStore.migrateLegacyInvite(contactId)
        repository.markInvitation(contactId, true)
        repository.setActiveContact(contactId)
    }
}

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val settings = SettingsStore(this)
        AppCompatDelegate.setDefaultNightMode(ThemeController.delegateMode(settings.current.theme))
        MessageNotifications.ensureChannels(this)
        container = AppContainer(this, settings)
    }
}
