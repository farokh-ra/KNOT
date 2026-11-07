package dev.twoperson.messenger.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Application-level message schema carried inside the encrypted peer channel.
 *
 * Every field is length checked on decode. Unknown types, oversized payloads, invalid UTF-8
 * and absurd timestamps are rejected rather than coerced.
 */
object Protocol {

    /** Must match [dev.twoperson.messenger.net.Wire.VERSION]; a mismatch is rejected in Hello. */
    const val WIRE_VERSION = 2

    const val TYPE_HELLO: Int = 1
    const val TYPE_TEXT: Int = 2
    const val TYPE_ACK: Int = 3

    const val DEVICE_ID_BYTES = 16
    const val MESSAGE_ID_BYTES = 16
    const val PUBLIC_KEY_BYTES = 32

    const val MAX_TEXT_CODE_POINTS = 2000
    const val MAX_TEXT_BYTES = 8 * 1024
    const val MAX_NAME_BYTES = 64
    const val MAX_APP_MESSAGE_BYTES = 16 * 1024

    /** Timestamps outside this window are treated as clock abuse, not as content. */
    const val MIN_TIMESTAMP_MS = 1_000_000_000_000L // 2001-09-09
    const val MAX_TIMESTAMP_MS = 4_102_444_800_000L // 2100-01-01
}

sealed interface AppMessage {

    data class Hello(
        val version: Int,
        val deviceId: ByteArray,
        val identityPublicKey: ByteArray,
        val displayName: String
    ) : AppMessage {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Hello && version == other.version &&
                deviceId.contentEquals(other.deviceId) &&
                identityPublicKey.contentEquals(other.identityPublicKey) &&
                displayName == other.displayName)

        override fun hashCode(): Int = 31 * (31 * (31 * version + deviceId.contentHashCode()) +
            identityPublicKey.contentHashCode()) + displayName.hashCode()
    }

    data class Text(
        val messageId: ByteArray,
        val sequence: Long,
        val createdAt: Long,
        val body: String
    ) : AppMessage {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Text && messageId.contentEquals(other.messageId) &&
                sequence == other.sequence && createdAt == other.createdAt && body == other.body)

        override fun hashCode(): Int = 31 * (31 * (31 * messageId.contentHashCode() + sequence.toInt()) +
            createdAt.hashCode()) + body.hashCode()
    }

    data class Ack(val messageId: ByteArray) : AppMessage {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Ack && messageId.contentEquals(other.messageId))

        override fun hashCode(): Int = messageId.contentHashCode()
    }
}

object AppCodec {

    fun encode(message: AppMessage): ByteArray = when (message) {
        is AppMessage.Hello -> {
            val name = message.displayName.toByteArray(StandardCharsets.UTF_8)
            require(name.size <= Protocol.MAX_NAME_BYTES) { "display name too long" }
            require(message.deviceId.size == Protocol.DEVICE_ID_BYTES) { "bad device id" }
            require(message.identityPublicKey.size == Protocol.PUBLIC_KEY_BYTES) { "bad identity key" }
            ByteBuffer.allocate(1 + 2 + Protocol.DEVICE_ID_BYTES + Protocol.PUBLIC_KEY_BYTES + 1 + name.size)
                .put(Protocol.TYPE_HELLO.toByte())
                .putShort(message.version.toShort())
                .put(message.deviceId)
                .put(message.identityPublicKey)
                .put(name.size.toByte())
                .put(name)
                .array()
        }

        is AppMessage.Text -> {
            val body = message.body.toByteArray(StandardCharsets.UTF_8)
            require(body.size <= Protocol.MAX_TEXT_BYTES) { "text too long" }
            require(message.messageId.size == Protocol.MESSAGE_ID_BYTES) { "bad message id" }
            ByteBuffer.allocate(1 + Protocol.MESSAGE_ID_BYTES + 8 + 8 + 2 + body.size)
                .put(Protocol.TYPE_TEXT.toByte())
                .put(message.messageId)
                .putLong(message.sequence)
                .putLong(message.createdAt)
                .putShort(body.size.toShort())
                .put(body)
                .array()
        }

        is AppMessage.Ack -> {
            require(message.messageId.size == Protocol.MESSAGE_ID_BYTES) { "bad message id" }
            ByteBuffer.allocate(1 + Protocol.MESSAGE_ID_BYTES)
                .put(Protocol.TYPE_ACK.toByte())
                .put(message.messageId)
                .array()
        }
    }

    /** Returns null for anything the schema does not accept. */
    fun decode(bytes: ByteArray): AppMessage? {
        if (bytes.isEmpty() || bytes.size > Protocol.MAX_APP_MESSAGE_BYTES) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes)
            when (buffer.get().toInt() and 0xFF) {
                Protocol.TYPE_HELLO -> decodeHello(buffer)
                Protocol.TYPE_TEXT -> decodeText(buffer)
                Protocol.TYPE_ACK -> decodeAck(buffer)
                else -> null
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun decodeHello(buffer: ByteBuffer): AppMessage.Hello? {
        if (buffer.remaining() < 2 + Protocol.DEVICE_ID_BYTES + Protocol.PUBLIC_KEY_BYTES + 1) return null
        val version = buffer.short.toInt() and 0xFFFF
        if (version != Protocol.WIRE_VERSION) return null
        val deviceId = ByteArray(Protocol.DEVICE_ID_BYTES).also { buffer.get(it) }
        val publicKey = ByteArray(Protocol.PUBLIC_KEY_BYTES).also { buffer.get(it) }
        val nameLength = buffer.get().toInt() and 0xFF
        if (nameLength > Protocol.MAX_NAME_BYTES || buffer.remaining() != nameLength) return null
        val nameBytes = ByteArray(nameLength).also { buffer.get(it) }
        val name = decodeUtf8(nameBytes) ?: return null
        val sanitized = name.filterNot { it.isISOControl() }.trim()
        return AppMessage.Hello(version, deviceId, publicKey, sanitized)
    }

    private fun decodeText(buffer: ByteBuffer): AppMessage.Text? {
        if (buffer.remaining() < Protocol.MESSAGE_ID_BYTES + 8 + 8 + 2) return null
        val messageId = ByteArray(Protocol.MESSAGE_ID_BYTES).also { buffer.get(it) }
        val sequence = buffer.long
        val createdAt = buffer.long
        val bodyLength = buffer.short.toInt() and 0xFFFF
        if (bodyLength > Protocol.MAX_TEXT_BYTES || buffer.remaining() != bodyLength) return null
        if (sequence < 0) return null
        if (createdAt < Protocol.MIN_TIMESTAMP_MS || createdAt > Protocol.MAX_TIMESTAMP_MS) return null
        val bodyBytes = ByteArray(bodyLength).also { buffer.get(it) }
        val body = decodeUtf8(bodyBytes) ?: return null
        if (body.isEmpty()) return null
        val codePoints = body.codePointCount(0, body.length)
        if (codePoints > Protocol.MAX_TEXT_CODE_POINTS) return null
        return AppMessage.Text(messageId, sequence, createdAt, body)
    }

    private fun decodeAck(buffer: ByteBuffer): AppMessage.Ack? {
        if (buffer.remaining() != Protocol.MESSAGE_ID_BYTES) return null
        val messageId = ByteArray(Protocol.MESSAGE_ID_BYTES).also { buffer.get(it) }
        return AppMessage.Ack(messageId)
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: Exception) {
        null
    }
}
