package dev.twoperson.messenger.ui

import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Message
import dev.twoperson.messenger.data.MessageDirection
import dev.twoperson.messenger.data.MessageState
import dev.twoperson.messenger.databinding.ItemMessageBinding
import java.util.Date

class ChatAdapter : ListAdapter<Message, ChatAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    fun submit(messages: List<Message>) {
        submitList(messages)
    }

    class Holder(private val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val context = binding.root.context
            val outgoing = message.direction == MessageDirection.OUTGOING

            binding.textBody.text = message.body
            binding.textBody.setTextColor(
                ContextCompat.getColor(context, if (outgoing) R.color.bubble_out_text else R.color.bubble_in_text)
            )
            binding.bubbleCard.setCardBackgroundColor(
                ContextCompat.getColor(context, if (outgoing) R.color.bubble_out else R.color.bubble_in)
            )
            binding.textMeta.setTextColor(
                ContextCompat.getColor(context, if (outgoing) R.color.bubble_out_text else R.color.bubble_in_text)
            )

            val layoutParams = (binding.bubbleCard.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = if (outgoing) Gravity.END else Gravity.START
            }
            binding.bubbleCard.layoutParams = layoutParams

            val time = DateFormat.getTimeFormat(context).format(Date(message.createdAt))
            binding.textMeta.text = if (outgoing) {
                context.getString(R.string.message_meta_format, time, stateLabel(context, message.state))
            } else {
                time
            }
        }

        private fun stateLabel(context: android.content.Context, state: MessageState): String =
            context.getString(
                when (state) {
                    MessageState.PENDING -> R.string.message_state_pending
                    MessageState.SENT -> R.string.message_state_sent
                    MessageState.DELIVERED -> R.string.message_state_delivered
                    MessageState.FAILED -> R.string.message_state_failed
                }
            )
    }

    private object Diff : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem == newItem
    }
}
