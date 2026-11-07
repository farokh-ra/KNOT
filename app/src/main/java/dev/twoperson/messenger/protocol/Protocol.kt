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
    const val WIRE_VERSION = 4

    const val TYPE_HELLO: Int = 1
    const val TYPE_TEXT: Int = 2
    const val TYPE_ACK: Int = 3

    /** File transfer messages. Data travels inside the same encrypted reliable channel. */
    const val TYPE_FILE_OFFER: Int = 4
    const val TYPE_FILE_DATA: Int = 5
    const val TYPE_FILE_END: Int = 6
    const val TYPE_FILE_ACK: Int = 7
    const val TYPE_FILE_DONE: Int = 8

    /** Failure codes carried by [AppMessage.FileDone]. */
    const val FILE_DONE_REJECTED: Int = 1
    const val FILE_DONE_INTEGRITY: Int = 2

    const val DEVICE_ID_BYTES = 16
    const val MESSAGE_ID_BYTES = 16
    const val TRANSFER_ID_BYTES = 16
    const val PUBLIC_KEY_BYTES = 32

    const val MAX_TEXT_CODE_POINTS = 2000
    const val MAX_TEXT_BYTES = 8 * 1024
    const val MAX_NAME_BYTES = 64
    const val MAX_FILE_NAME_BYTES = 255
    const val MAX_MIME_BYTES = 128
    const val MAX_APP_MESSAGE_BYTES = 64 * 1024

    /** One file data block. Kept well below the app-message cap so control messages can interleave. */
    const val MAX_FILE_DATA_BYTES = 16 * 1024

    /** Sanity bound: larger values are rejected instead of filling a receiver's disk silently. */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 * 1024 // 4 TiB

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

    /**
     * Announces an outgoing file and re-announces it after every reconnect. The receiver replies
     * with [FileAck] carrying the number of bytes it already has, so a transfer resumes instead
     * of starting over.
     */
    data class FileOffer(
        val transferId: ByteArray,
        val messageId: ByteArray,
        val sequence: Long,
        val createdAt: Long,
        val totalBytes: Long,
        val fileName: String,
        val mimeType: String
    ) : AppMessage {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is FileOffer && transferId.contentEquals(other.transferId) &&
                messageId.contentEquals(other.messageId) && sequence == other.sequence &&
                createdAt == other.createdAt && totalBytes == other.totalBytes &&
                fileName == other.fileName && mimeType == other.mimeType)

        override fun hashCode(): Int = 31 * (31 * (31 * transferId.contentHashCode() +
            messageId.contentHashCode()) + totalBytes.hashCode()) + fileName.hashCode()
    }

    /** One sequential block of file bytes. Offsets start at zero and never overlap. */
    data class FileData(
        val transferId: ByteArray,
        val offset: Long,
        val data: ByteArray
    ) : AppMessage {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is FileData && transferId.contentEquals(other.transferId) &&
                offset == other.offset && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * (31 * transferId.contentHashCode() +
            offset.hashCode()) + data.contentHashCode()
    }

    /**
     * Sender -> receiver: all bytes have been sent. [digest] is the FNV-1a value of the whole
     * file; the receiver compares it with its own before moving the part file into place.
     */
    data class FileEnd(
        val transferId: ByteArray,
        val digest: Long = FileDigest.OFFSET_BASIS
    ) : AppMessage {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is FileEnd && transferId.contentEquals(other.transferId) && digest == other.digest)

        override fun hashCode(): Int = 31 * transferId.contentHashCode() + digest.hashCode()
    }

    /**
     * Receiver -> sender: durable byte count and the digest of the bytes held so far. The
     * digest lets the sender resume without re-reading the prefix; a zero offset with a fresh
     * digest tells it that an integrity retry starts from the beginning.
     */
    data class FileAck(
        val transferId: ByteArray,
        val receivedBytes: Long,
        val digest: Long = FileDigest.OFFSET_BASIS
    ) : AppMessage {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is FileAck && transferId.contentEquals(other.transferId) &&
                receivedBytes == other.receivedBytes && digest == other.digest)

        override fun hashCode(): Int =
            31 * (31 * transferId.contentHashCode() + receivedBytes.hashCode()) + digest.hashCode()
    }

    /**
     * Receiver -> sender: the file was stored (or rejected). [reason] distinguishes a permanent
     * rejection (for example no free space) from an integrity mismatch that is worth retrying.
     */
    data class FileDone(
        val transferId: ByteArray,
        val success: Boolean,
        val reason: Int = 0
    ) : AppMessage {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is FileDone && transferId.contentEquals(other.transferId) &&
                success == other.success && reason == other.reason)

        override fun hashCode(): Int =
            31 * (31 * transferId.contentHashCode() + success.hashCode()) + reason
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

        is AppMessage.FileOffer -> {
            require(message.transferId.size == Protocol.TRANSFER_ID_BYTES) { "bad transfer id" }
            require(message.messageId.size == Protocol.MESSAGE_ID_BYTES) { "bad message id" }
            require(message.sequence >= 0) { "bad sequence" }
            require(message.createdAt in Protocol.MIN_TIMESTAMP_MS..Protocol.MAX_TIMESTAMP_MS) {
                "bad timestamp"
            }
            require(message.totalBytes in 0..Protocol.MAX_FILE_BYTES) { "bad file size" }
            val name = message.fileName.toByteArray(StandardCharsets.UTF_8)
            val mime = (message.mimeType.ifBlank { "application/octet-stream" })
                .toByteArray(StandardCharsets.UTF_8)
            require(name.isNotEmpty() && name.size <= Protocol.MAX_FILE_NAME_BYTES) { "bad file name" }
            require(mime.size <= Protocol.MAX_MIME_BYTES) { "bad mime type" }
            ByteBuffer.allocate(
                1 + Protocol.TRANSFER_ID_BYTES + Protocol.MESSAGE_ID_BYTES + 8 + 8 + 8 + 1 +
                    name.size + 1 + mime.size
            )
                .put(Protocol.TYPE_FILE_OFFER.toByte())
                .put(message.transferId)
                .put(message.messageId)
                .putLong(message.sequence)
                .putLong(message.createdAt)
                .putLong(message.totalBytes)
                .put(name.size.toByte())
                .put(name)
                .put(mime.size.toByte())
                .put(mime)
                .array()
        }

        is AppMessage.FileData -> {
            require(message.transferId.size == Protocol.TRANSFER_ID_BYTES) { "bad transfer id" }
            require(message.offset >= 0) { "bad offset" }
            require(message.data.isNotEmpty() && message.data.size <= Protocol.MAX_FILE_DATA_BYTES) {
                "bad file block"
            }
            ByteBuffer.allocate(1 + Protocol.TRANSFER_ID_BYTES + 8 + 4 + message.data.size)
                .put(Protocol.TYPE_FILE_DATA.toByte())
                .put(message.transferId)
                .putLong(message.offset)
                .putInt(message.data.size)
                .put(message.data)
                .array()
        }

        is AppMessage.FileEnd -> {
            require(message.transferId.size == Protocol.TRANSFER_ID_BYTES) { "bad transfer id" }
            ByteBuffer.allocate(1 + Protocol.TRANSFER_ID_BYTES + 8)
                .put(Protocol.TYPE_FILE_END.toByte())
                .put(message.transferId)
                .putLong(message.digest)
                .array()
        }

        is AppMessage.FileAck -> {
            require(message.transferId.size == Protocol.TRANSFER_ID_BYTES) { "bad transfer id" }
            require(message.receivedBytes >= 0) { "bad received byte count" }
            ByteBuffer.allocate(1 + Protocol.TRANSFER_ID_BYTES + 8 + 8)
                .put(Protocol.TYPE_FILE_ACK.toByte())
                .put(message.transferId)
                .putLong(message.receivedBytes)
                .putLong(message.digest)
                .array()
        }

        is AppMessage.FileDone -> {
            require(message.transferId.size == Protocol.TRANSFER_ID_BYTES) { "bad transfer id" }
            require(message.reason in 0..Protocol.FILE_DONE_INTEGRITY) { "bad reason" }
            ByteBuffer.allocate(1 + Protocol.TRANSFER_ID_BYTES + 1 + 1)
                .put(Protocol.TYPE_FILE_DONE.toByte())
                .put(message.transferId)
                .put(if (message.success) 1 else 0)
                .put(message.reason.toByte())
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
                Protocol.TYPE_FILE_OFFER -> decodeFileOffer(buffer)
                Protocol.TYPE_FILE_DATA -> decodeFileData(buffer)
                Protocol.TYPE_FILE_END -> decodeFileEnd(buffer)
                Protocol.TYPE_FILE_ACK -> decodeFileAck(buffer)
                Protocol.TYPE_FILE_DONE -> decodeFileDone(buffer)
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

    private fun decodeFileOffer(buffer: ByteBuffer): AppMessage.FileOffer? {
        val fixed = Protocol.TRANSFER_ID_BYTES + Protocol.MESSAGE_ID_BYTES + 8 + 8 + 8 + 1
        if (buffer.remaining() < fixed) return null
        val transferId = ByteArray(Protocol.TRANSFER_ID_BYTES).also { buffer.get(it) }
        val messageId = ByteArray(Protocol.MESSAGE_ID_BYTES).also { buffer.get(it) }
        val sequence = buffer.long
        val createdAt = buffer.long
        val totalBytes = buffer.long
        if (sequence < 0) return null
        if (createdAt < Protocol.MIN_TIMESTAMP_MS || createdAt > Protocol.MAX_TIMESTAMP_MS) return null
        if (totalBytes < 0 || totalBytes > Protocol.MAX_FILE_BYTES) return null

        val nameLength = buffer.get().toInt() and 0xFF
        if (nameLength == 0 || nameLength > Protocol.MAX_FILE_NAME_BYTES) return null
        if (buffer.remaining() < nameLength + 1) return null
        val nameBytes = ByteArray(nameLength).also { buffer.get(it) }
        val rawName = decodeUtf8(nameBytes) ?: return null
        val name = sanitizeFileName(rawName) ?: return null
        val mimeLength = buffer.get().toInt() and 0xFF
        if (buffer.remaining() != mimeLength || mimeLength > Protocol.MAX_MIME_BYTES) return null
        val mimeBytes = ByteArray(mimeLength).also { buffer.get(it) }
        val rawMime = if (mimeLength == 0) "" else decodeUtf8(mimeBytes) ?: return null
        val mime = rawMime.filterNot { it.isISOControl() }.trim()
            .ifEmpty { "application/octet-stream" }
        return AppMessage.FileOffer(transferId, messageId, sequence, createdAt, totalBytes, name, mime)
    }

    private fun decodeFileData(buffer: ByteBuffer): AppMessage.FileData? {
        if (buffer.remaining() < Protocol.TRANSFER_ID_BYTES + 8 + 4) return null
        val transferId = ByteArray(Protocol.TRANSFER_ID_BYTES).also { buffer.get(it) }
        val offset = buffer.long
        val length = buffer.int
        if (offset < 0 || offset > Protocol.MAX_FILE_BYTES) return null
        if (length <= 0 || length > Protocol.MAX_FILE_DATA_BYTES) return null
        if (buffer.remaining() != length) return null
        val data = ByteArray(length).also { buffer.get(it) }
        return AppMessage.FileData(transferId, offset, data)
    }

    private fun decodeFileEnd(buffer: ByteBuffer): AppMessage.FileEnd? {
        if (buffer.remaining() != Protocol.TRANSFER_ID_BYTES + 8) return null
        val transferId = ByteArray(Protocol.TRANSFER_ID_BYTES).also { buffer.get(it) }
        return AppMessage.FileEnd(transferId, buffer.long)
    }

    private fun decodeFileAck(buffer: ByteBuffer): AppMessage.FileAck? {
        if (buffer.remaining() != Protocol.TRANSFER_ID_BYTES + 8 + 8) return null
        val transferId = ByteArray(Protocol.TRANSFER_ID_BYTES).also { buffer.get(it) }
        val received = buffer.long
        val digest = buffer.long
        if (received < 0 || received > Protocol.MAX_FILE_BYTES) return null
        return AppMessage.FileAck(transferId, received, digest)
    }

    private fun decodeFileDone(buffer: ByteBuffer): AppMessage.FileDone? {
        if (buffer.remaining() != Protocol.TRANSFER_ID_BYTES + 1 + 1) return null
        val transferId = ByteArray(Protocol.TRANSFER_ID_BYTES).also { buffer.get(it) }
        val success = buffer.get().toInt() != 0
        val reason = buffer.get().toInt() and 0xFF
        if (reason > Protocol.FILE_DONE_INTEGRITY) return null
        return AppMessage.FileDone(transferId, success, reason)
    }

    /**
     * Trims a display name to something safe for a filesystem while keeping Unicode intact.
     * The receiver prepends its own transfer id, so this is presentation, not a path.
     */
    fun sanitizeFileName(raw: String): String? {
        var cleaned = raw
            .filterNot { it.isISOControl() || it == '/' || it == '\\' || it == '\u0000' }
            .trim()
            .trim('.')
        while (cleaned.isNotEmpty() &&
            cleaned.toByteArray(StandardCharsets.UTF_8).size > Protocol.MAX_FILE_NAME_BYTES
        ) {
            cleaned = cleaned.dropLast(1)
        }
        return cleaned.ifEmpty { null }
    }

    /**
     * Normalizes an arbitrary provider MIME value to the wire limit without splitting a UTF-8
     * character (the limit is in encoded bytes, not Kotlin characters).
     */
    fun sanitizeMimeType(raw: String): String {
        var cleaned = raw
            .filterNot { it.isISOControl() }
            .trim()
            .ifEmpty { "application/octet-stream" }
        while (cleaned.toByteArray(StandardCharsets.UTF_8).size > Protocol.MAX_MIME_BYTES) {
            cleaned = cleaned.dropLast(1)
        }
        return cleaned.ifEmpty { "application/octet-stream" }
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
