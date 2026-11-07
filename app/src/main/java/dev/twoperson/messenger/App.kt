package dev.twoperson.messenger

import android.app.Application
import android.content.Context
import dev.twoperson.messenger.data.Db
import dev.twoperson.messenger.data.MessageRepository
import dev.twoperson.messenger.data.SecureStore
import dev.twoperson.messenger.engine.ConnectionEngine
import dev.twoperson.messenger.service.IncomingNotifier
import dev.twoperson.messenger.service.MessageNotifications

/**
 * Single application-scoped container. Everything here is device local: no analytics,
 * no crash reporting, no network client that talks to a backend.
 */
class AppContainer(context: Context) {
    val database: Db = Db(context)
    val repository: MessageRepository = MessageRepository(database)
    val secureStore: SecureStore = SecureStore(context)
    val notifier: IncomingNotifier = IncomingNotifier(context, repository)
    val engine: ConnectionEngine = ConnectionEngine(
        context,
        secureStore,
        repository,
        onIncomingMessage = { message -> notifier.onIncoming(message) }
    )
}

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MessageNotifications.ensureChannels(this)
        container = AppContainer(this)
    }
}
