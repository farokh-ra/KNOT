package dev.twoperson.messenger.data

enum class MessageDirection { OUTGOING, INCOMING }

/** Delivery state of an outgoing message. Incoming messages are stored as DELIVERED. */
enum class MessageState { PENDING, SENT, DELIVERED, FAILED }

enum class MessageKind { TEXT, FILE }

data class Message(
    val id: String,
    val contactId: String,
    val direction: MessageDirection,
    val body: String,
    val createdAt: Long,
    val sequence: Long,
    val state: MessageState,
    val senderId: String,
    val kind: MessageKind = MessageKind.TEXT,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long = 0L,
    /** Sender only: the persisted Storage Access Framework URI of the source file. */
    val fileUri: String? = null,
    /** Receiver only (and sender cache fallback): the local stored path. */
    val filePath: String? = null,
    val transferId: String? = null,
    /** Bytes durably acknowledged by the receiver, or received locally for an incoming file. */
    val receivedBytes: Long = 0L,
    /**
     * Receiver only: FNV-1a digest of the bytes held at [receivedBytes]. Persisting it means a
     * resumed transfer can continue without re-reading the partial file. Zero means "not
     * calculated yet".
     */
    val transferDigest: Long = 0L,
    val isRead: Boolean = true
)

/**
 * A locally saved contact. A contact owns one private conversation. Pairing remains strictly
 * one-to-one: a contact can only ever represent the single device it was paired with.
 */
data class Contact(
    val id: String,
    /** Name chosen on this device. Never sent to the peer. */
    val label: String? = null,
    /** Optional private note, shown only on this device. */
    val note: String? = null,
    val deviceId: String? = null,
    val publicKeyHex: String? = null,
    val displayName: String? = null,
    val pairedAt: Long = 0L,
    val createdAt: Long = 0L,
    val lastOpenedAt: Long = 0L,
    val hasInvitation: Boolean = false
) {
    /** Best available name: local label, then the device's self-reported name. */
    val name: String
        get() = label?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Unnamed contact"

    /** True once a peer identity has actually been pinned by a successful connection. */
    val isPaired: Boolean
        get() = pairedAt > 0 && (!publicKeyHex.isNullOrBlank() || !deviceId.isNullOrBlank())

    /** True when this contact has an active invitation or a completed pairing. */
    val isOnKnot: Boolean
        get() = hasInvitation || isPaired || !deviceId.isNullOrBlank()

    /** Device name when it differs from the chosen contact name. */
    val deviceLine: String?
        get() = displayName?.trim()?.takeIf { it.isNotEmpty() && it != name }
}

/** One row on the Chats tab. */
data class ChatSummary(
    val contact: Contact,
    val lastMessage: Message?,
    val unreadCount: Int
) {
    val sortTime: Long
        get() = lastMessage?.createdAt ?: contact.pairedAt.takeIf { it > 0 } ?: contact.createdAt
}

/** Pure helpers kept out of the activities so they can be unit tested on the JVM. */
object ContactNames {

    const val MAX_LABEL_LENGTH = 40
    const val MAX_NOTE_LENGTH = 160

    /** Trims, collapses whitespace and caps a locally entered name. */
    fun sanitizeLabel(raw: String?): String? {
        val cleaned = raw.orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_LABEL_LENGTH)
            .trim()
        return cleaned.ifEmpty { null }
    }

    fun sanitizeNote(raw: String?): String? {
        val cleaned = raw.orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_NOTE_LENGTH)
            .trim()
        return cleaned.ifEmpty { null }
    }

    /** A stable single-letter avatar. Falls back to the app initial. */
    fun initial(name: String?): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return "K"
        val first = trimmed.firstOrNull { it.isLetterOrDigit() } ?: trimmed.first()
        return first.uppercase()
    }

    /** Case-insensitive search used by the Contacts tab. */
    fun matches(contact: Contact, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        val haystack = listOfNotNull(
            contact.label,
            contact.displayName,
            contact.deviceLine,
            contact.note
        ).joinToString(" ").lowercase()
        return haystack.contains(needle.lowercase())
    }
}

/** Formats a chat-list preview without touching Android resources. */
object ChatPreview {

    fun build(
        message: Message?,
        youPrefix: String,
        filePrefix: String,
        emptyLabel: String
    ): String {
        if (message == null) return emptyLabel
        val content = when (message.kind) {
            MessageKind.FILE -> filePrefix.format(message.fileName ?: message.body)
            MessageKind.TEXT -> message.body
        }
        return if (message.direction == MessageDirection.OUTGOING) {
            youPrefix.format(content)
        } else {
            content
        }
    }
}
