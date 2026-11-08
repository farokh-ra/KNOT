package dev.twoperson.messenger.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.ChatSummary
import dev.twoperson.messenger.data.Contact
import dev.twoperson.messenger.databinding.FragmentChatsBinding
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.service.ConnectionService
import kotlinx.coroutines.launch

/** Chats tab: the active connection, all conversations, and the new-conversation flow. */
class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ChatsAdapter
    private var chats: List<ChatSummary> = emptyList()
    private var activeContact: Contact? = null
    private var latestStatus = ConnectionStatus()

    private val container get() = (requireActivity() as HomeActivity).container

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatsBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChatsAdapter(onOpen = ::openChat, onMore = ::showMenu)
        binding.listChats.layoutManager = LinearLayoutManager(requireContext())
        binding.listChats.adapter = adapter

        binding.fabNewChat.setOnClickListener { createContact(join = false) }
        binding.buttonEmptyNew.setOnClickListener { createContact(join = false) }
        binding.buttonEmptyJoin.setOnClickListener { createContact(join = true) }
        binding.cardActive.setOnClickListener { openActive() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.repository.chats.collect { list ->
                        chats = list
                        render()
                    }
                }
                launch {
                    container.repository.activeContact.collect { contact ->
                        activeContact = contact
                        renderActive()
                    }
                }
                launch {
                    container.engine.status.collect { status ->
                        latestStatus = status
                        renderActive()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            container.repository.refresh()
            render()
        }
    }

    override fun onDestroyView() {
        binding.listChats.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val hasChats = chats.isNotEmpty()
        binding.listChats.isVisible = hasChats
        binding.emptyState.isVisible = !hasChats
        binding.fabNewChat.isVisible = hasChats
        adapter.submitList(chats)
        renderActive()
    }

    private fun renderActive() {
        val contact = activeContact
        if (contact == null || !contact.isOnKnot) {
            binding.cardActive.isVisible = false
            return
        }
        binding.cardActive.isVisible = true

        binding.textActiveName.text = contact.name
        val status = StatusUi.of(latestStatus)
        binding.textActiveStatus.setText(
            if (contact.hasInvitation) status.label else R.string.state_not_paired
        )
        val color = if (contact.hasInvitation) status.color else R.color.status_idle
        binding.activeStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), color)
        )
    }

    private fun openActive() {
        val contact = activeContact ?: return
        if (contact.hasInvitation) openChat(contact) else openDetail(contact)
    }

    private fun openChat(contact: Contact) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!container.engine.selectContact(contact.id)) {
                Toast.makeText(requireContext(), R.string.chat_missing_contact, Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(requireContext(), ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_CONTACT_ID, contact.id)
            )
        }
    }

    private fun openDetail(contact: Contact) {
        startActivity(
            Intent(requireContext(), ContactDetailActivity::class.java)
                .putExtra(ContactDetailActivity.EXTRA_CONTACT_ID, contact.id)
        )
    }

    private fun createContact(join: Boolean) {
        if (!isAdded) return
        ContactDialogs.show(
            context = requireContext(),
            title = R.string.chats_new_contact_title,
            message = R.string.chats_new_contact_body,
            positive = R.string.chats_new_contact_create
        ) { name, note ->
            viewLifecycleOwner.lifecycleScope.launch {
                val contact = container.repository.addContact(name, note)
                container.engine.selectContact(contact.id)
                Toast.makeText(requireContext(), R.string.chats_new_contact_added, Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(requireContext(), PairingActivity::class.java)
                        .putExtra(PairingActivity.EXTRA_CONTACT_ID, contact.id)
                        .putExtra(
                            PairingActivity.EXTRA_MODE,
                            if (join) PairingActivity.MODE_JOIN else PairingActivity.MODE_CREATE
                        )
                )
            }
        }
    }

    private fun showMenu(anchor: View, contact: Contact) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_OPEN, 0, R.string.chats_open_info)
        if (contact.isOnKnot) {
            popup.menu.add(0, MENU_CHAT, 1, R.string.contact_open_chat)
        }
        val chat = chats.firstOrNull { it.contact.id == contact.id }
        if ((chat?.unreadCount ?: 0) > 0) {
            popup.menu.add(0, MENU_READ, 2, R.string.chats_mark_read)
        }
        popup.menu.add(0, MENU_RENAME, 3, R.string.chats_rename_title)
        popup.menu.add(0, MENU_CLEAR, 4, R.string.chats_clear_ok)
        popup.menu.add(0, MENU_DELETE, 5, R.string.chats_delete_ok)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN -> openDetail(contact)
                MENU_CHAT -> openChat(contact)
                MENU_READ -> viewLifecycleOwner.lifecycleScope.launch {
                    container.repository.markIncomingRead(contact.id)
                }

                MENU_RENAME -> rename(contact)
                MENU_CLEAR -> confirmClear(contact)
                MENU_DELETE -> confirmDelete(contact)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun rename(contact: Contact) {
        ContactDialogs.show(
            context = requireContext(),
            title = R.string.chats_rename_title,
            message = R.string.chats_rename_hint,
            initialName = contact.label ?: contact.displayName,
            initialNote = contact.note,
            showNote = false,
            positive = R.string.chats_rename_save
        ) { name, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                container.repository.updateContact(contact.id, name, contact.note)
                Toast.makeText(requireContext(), R.string.chats_renamed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmClear(contact: Contact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chats_clear_title)
            .setMessage(R.string.chats_clear_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chats_clear_ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.engine.clearConversation(contact.id)
                    Toast.makeText(requireContext(), R.string.chats_cleared, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun confirmDelete(contact: Contact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chats_delete_title)
            .setMessage(R.string.chats_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chats_delete_ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.engine.deleteContact(contact.id)
                    ConnectionService.sync(requireContext())
                    Toast.makeText(requireContext(), R.string.chats_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    companion object {
        private const val MENU_OPEN = 101
        private const val MENU_CHAT = 102
        private const val MENU_READ = 103
        private const val MENU_RENAME = 104
        private const val MENU_CLEAR = 105
        private const val MENU_DELETE = 106
    }
}
