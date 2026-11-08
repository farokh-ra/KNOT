package dev.twoperson.messenger.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.ChatPreview
import dev.twoperson.messenger.data.ChatSummary
import dev.twoperson.messenger.data.Contact
import dev.twoperson.messenger.data.ContactNames
import dev.twoperson.messenger.databinding.ItemChatBinding

/** The conversation list. One row is one contact and its private thread. */
class ChatsAdapter(
    private val onOpen: (Contact) -> Unit,
    private val onMore: (View, Contact) -> Unit
) : ListAdapter<ChatSummary, ChatsAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onOpen, onMore)
    }

    class Holder(private val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            summary: ChatSummary,
            onOpen: (Contact) -> Unit,
            onMore: (View, Contact) -> Unit
        ) {
            val context = binding.root.context
            val contact = summary.contact
            binding.textAvatar.text = ContactNames.initial(contact.name)
            binding.textName.text = contact.name
            binding.textPreview.text = ChatPreview.build(
                summary.lastMessage,
                context.getString(R.string.home_preview_you),
                context.getString(R.string.home_file_preview),
                context.getString(R.string.chat_preview_empty)
            )
            val timestamp = summary.sortTime
            binding.textTime.text = if (timestamp > 0) {
                UiTime.conversationTime(context, timestamp)
            } else {
                ""
            }
            val unread = summary.unreadCount
            binding.textUnread.isVisible = unread > 0
            binding.textUnread.text = if (unread > 99) "99+" else unread.toString()
            binding.textUnread.contentDescription = if (unread > 0) {
                context.resources.getQuantityString(
                    R.plurals.chats_unread_content_description,
                    unread,
                    unread
                )
            } else {
                null
            }
            binding.root.contentDescription = "${contact.name}. ${binding.textPreview.text}"
            binding.root.setOnClickListener { onOpen(contact) }
            binding.buttonMore.setOnClickListener { anchor -> onMore(anchor, contact) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatSummary>() {
        override fun areItemsTheSame(oldItem: ChatSummary, newItem: ChatSummary): Boolean =
            oldItem.contact.id == newItem.contact.id

        override fun areContentsTheSame(oldItem: ChatSummary, newItem: ChatSummary): Boolean =
            oldItem == newItem
    }
}
