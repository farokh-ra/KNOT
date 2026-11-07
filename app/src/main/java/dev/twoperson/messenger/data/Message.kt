package dev.twoperson.messenger.data

enum class MessageDirection { OUTGOING, INCOMING }

/** Delivery state of an outgoing message. Incoming messages are stored as DELIVERED. */
enum class MessageState { PENDING, SENT, DELIVERED, FAILED }

data class Message(
    val id: String,
    val direction: MessageDirection,
    val body: String,
    val createdAt: Long,
    val sequence: Long,
    val state: MessageState,
    val senderId: String
)

data class PeerInfo(
    val deviceId: String?,
    val publicKeyHex: String?,
    val label: String?,
    val displayName: String?,
    val pairedAt: Long
)
