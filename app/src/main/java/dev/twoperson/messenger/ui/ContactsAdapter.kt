package dev.twoperson.messenger.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Contact
import dev.twoperson.messenger.data.ContactNames
import dev.twoperson.messenger.databinding.ItemContactBinding

/** The contacts list. Includes local, invitation-only and paired contacts. */
class ContactsAdapter(
    private val onOpen: (Contact) -> Unit,
    private val onMore: (View, Contact) -> Unit
) : ListAdapter<Contact, ContactsAdapter.Holder>(Diff) {

    var activeContactId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), activeContactId, onOpen, onMore)
    }

    fun setActive(id: String?) {
        if (activeContactId == id) return
        activeContactId = id
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTIVE)
    }

    class Holder(private val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            contact: Contact,
            activeId: String?,
            onOpen: (Contact) -> Unit,
            onMore: (View, Contact) -> Unit
        ) {
            val context = binding.root.context
            binding.textAvatar.text = ContactNames.initial(contact.name)
            binding.textName.text = contact.name
            binding.textDetail.text = contact.note?.takeIf { it.isNotBlank() }
                ?: contact.deviceLine
                ?: context.getString(R.string.chats_contact_hint)
            binding.textState.setText(StatusUi.contactState(contact, activeId))
            binding.root.contentDescription = "${contact.name}. ${binding.textDetail.text}"
            binding.root.setOnClickListener { onOpen(contact) }
            binding.buttonMore.setOnClickListener { anchor -> onMore(anchor, contact) }
        }
    }

    companion object {
        private const val PAYLOAD_ACTIVE = "state"
    }

    private object Diff : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Contact, newItem: Contact): Boolean =
            oldItem == newItem
    }
}
