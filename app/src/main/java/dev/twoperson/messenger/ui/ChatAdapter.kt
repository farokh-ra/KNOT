package dev.twoperson.messenger.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.ShapeAppearanceModel
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Message
import dev.twoperson.messenger.data.MessageDirection
import dev.twoperson.messenger.data.MessageKind
import dev.twoperson.messenger.data.MessageState
import dev.twoperson.messenger.databinding.ItemMessageBinding
import java.util.Locale

class ChatAdapter(
    private val onFileClick: (Message) -> Unit,
    private val onMessageLongClick: (Message) -> Unit
) : ListAdapter<Message, ChatAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val previous = if (position > 0) getItem(position - 1) else null
        holder.bind(getItem(position), previous, onFileClick, onMessageLongClick)
    }

    fun submit(messages: List<Message>, commitCallback: (() -> Unit)? = null) {
        submitList(messages.toList(), commitCallback)
    }

    class Holder(private val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {

        private var speedMessageId: String? = null
        private var speedBytes = 0L
        private var speedAt = 0L
        private var speedBytesPerSecond = 0L

        fun bind(
            message: Message,
            previous: Message?,
            onFileClick: (Message) -> Unit,
            onMessageLongClick: (Message) -> Unit
        ) {
            val context = binding.root.context
            val outgoing = message.direction == MessageDirection.OUTGOING
            val isFile = message.kind == MessageKind.FILE

            bindDateHeader(context, message, previous)
            bindBubbleShape(context, message, previous)

            val contentColor = ContextCompat.getColor(
                context,
                if (outgoing) R.color.bubble_out_text else R.color.bubble_in_text
            )
            binding.bubbleCard.setCardBackgroundColor(
                ContextCompat.getColor(context, if (outgoing) R.color.bubble_out else R.color.bubble_in)
            )
            binding.bubbleCard.alpha = if (outgoing && message.state == MessageState.FAILED) 0.78f else 1f

            binding.textBody.isVisible = !isFile
            binding.fileRow.isVisible = isFile
            binding.textBody.text = if (isFile) "" else message.body
            binding.textBody.setTextColor(contentColor)
            // Long-press opens the message actions (copy, retry, delete). Text selection is
            // intentionally off so the gesture is not swallowed by the TextView.
            binding.textBody.setTextIsSelectable(false)

            val percent = progressPercent(message)
            binding.textFileName.text = message.fileName ?: message.body
            binding.textFileName.setTextColor(contentColor)
            binding.textFileInfo.setTextColor(contentColor)
            val sizeAndState = context.getString(
                R.string.file_size_and_state,
                formatSize(message.fileSize),
                fileStateLabel(context, message, percent, outgoing)
            )
            val speed = if (isFile && message.state == MessageState.PENDING) {
                sampleSpeed(message, System.currentTimeMillis())
            } else {
                resetSpeed()
                0L
            }
            binding.textFileInfo.text = if (speed > 0) {
                context.getString(R.string.file_info_with_speed, sizeAndState, formatSize(speed))
            } else {
                sizeAndState
            }
            binding.imageFileIcon.imageTintList = ColorStateList.valueOf(contentColor)
            binding.progressFile.progressTintList = ColorStateList.valueOf(contentColor)
            binding.progressFile.progress = percent
            binding.progressFile.isVisible = isFile &&
                message.state == MessageState.PENDING &&
                message.receivedBytes > 0 &&
                message.fileSize > 0

            binding.textMeta.setTextColor(contentColor)
            val time = UiTime.clock(context, message.createdAt)
            binding.textMeta.text = if (outgoing) {
                context.getString(R.string.message_meta_format, time, stateLabel(context, message.state))
            } else {
                time
            }

            val layoutParams = (binding.bubbleCard.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = if (outgoing) Gravity.END else Gravity.START
            }
            binding.bubbleCard.layoutParams = layoutParams

            if (isFile) {
                binding.bubbleCard.setOnClickListener { onFileClick(message) }
            } else {
                binding.bubbleCard.setOnClickListener(null)
            }
            binding.bubbleCard.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
            // Recycled rows keep listener state; explicit flags keep the ripple limited to
            // tappable file bubbles while every bubble remains long-pressable.
            binding.bubbleCard.isClickable = isFile
            binding.bubbleCard.isLongClickable = true
        }

        /**
         * Corner nearest the sender is tight only for the first bubble in a run, which makes
         * each side of the conversation read as one visual group.
         */
        private fun bindBubbleShape(context: Context, message: Message, previous: Message?) {
            val outgoing = message.direction == MessageDirection.OUTGOING
            val firstInRun = previous == null || previous.direction != message.direction ||
                !UiTime.sameDay(previous.createdAt, message.createdAt)
            val near = context.resources.getDimension(
                if (firstInRun) R.dimen.bubble_corner_tail else R.dimen.bubble_corner
            )
            val far = context.resources.getDimension(R.dimen.bubble_corner)
            binding.bubbleCard.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(if (outgoing) far else near)
                .setTopRightCornerSize(if (outgoing) near else far)
                .setBottomLeftCornerSize(far)
                .setBottomRightCornerSize(far)
                .build()
        }

        private fun bindDateHeader(context: Context, message: Message, previous: Message?) {
            val show = previous == null || !UiTime.sameDay(previous.createdAt, message.createdAt)
            binding.textDateHeader.isVisible = show
            if (show) binding.textDateHeader.text = UiTime.dayLabel(context, message.createdAt)
        }

        private fun sampleSpeed(message: Message, now: Long): Long {
            if (speedMessageId != message.id) {
                speedMessageId = message.id
                speedBytes = message.receivedBytes
                speedAt = now
                speedBytesPerSecond = 0L
                return 0L
            }
            val delta = message.receivedBytes - speedBytes
            val elapsed = now - speedAt
            if (delta > 0 && elapsed >= SPEED_SAMPLE_MS) {
                speedBytesPerSecond = delta * 1000L / elapsed
                speedBytes = message.receivedBytes
                speedAt = now
            }
            return speedBytesPerSecond
        }

        private fun resetSpeed() {
            speedMessageId = null
            speedBytes = 0L
            speedAt = 0L
            speedBytesPerSecond = 0L
        }

        private fun fileStateLabel(
            context: Context,
            message: Message,
            percent: Int,
            outgoing: Boolean
        ): String = context.getString(
            when {
                message.state == MessageState.FAILED -> R.string.file_state_failed
                message.state == MessageState.DELIVERED -> R.string.file_state_ready
                message.receivedBytes <= 0 -> R.string.file_state_waiting
                outgoing -> R.string.file_state_sending
                else -> R.string.file_state_receiving
            },
            if (message.receivedBytes <= 0 ||
                message.state == MessageState.FAILED ||
                message.state == MessageState.DELIVERED
            ) {
                0
            } else {
                percent
            }
        )

        private fun stateLabel(context: Context, state: MessageState): String =
            context.getString(
                when (state) {
                    MessageState.PENDING -> R.string.message_state_pending
                    MessageState.SENT -> R.string.message_state_sent
                    MessageState.DELIVERED -> R.string.message_state_delivered
                    MessageState.FAILED -> R.string.message_state_failed
                }
            )

        private fun progressPercent(message: Message): Int {
            if (message.fileSize <= 0) return 0
            return ((message.receivedBytes.coerceAtLeast(0) * 100) / message.fileSize)
                .toInt()
                .coerceIn(0, 100)
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            var value = bytes.toDouble()
            var unit = -1
            while (value >= 1024 && unit < UNITS.lastIndex) {
                value /= 1024
                unit++
            }
            return if (value >= 10) {
                String.format(Locale.getDefault(), "%.0f %s", value, UNITS[unit])
            } else {
                String.format(Locale.getDefault(), "%.1f %s", value, UNITS[unit])
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem == newItem
    }

    companion object {
        private val UNITS = arrayOf("KB", "MB", "GB", "TB")
        private const val SPEED_SAMPLE_MS = 400L
    }
}
