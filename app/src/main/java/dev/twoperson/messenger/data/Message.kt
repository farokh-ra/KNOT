package dev.twoperson.messenger.data

enum class MessageDirection { OUTGOING, INCOMING }

/** Delivery state of an outgoing message. Incoming messages are stored as DELIVERED. */
enum class MessageState { PENDING, SENT, DELIVERED, FAILED }

enum class MessageKind { TEXT, FILE }

data class Message(
    val id: String,
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

data class PeerInfo(
    val deviceId: String?,
    val publicKeyHex: String?,
    val label: String?,
    val displayName: String?,
    val pairedAt: Long
)
