package dev.twoperson.messenger.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Contact
import dev.twoperson.messenger.data.ContactNames
import dev.twoperson.messenger.databinding.FragmentContactsBinding
import dev.twoperson.messenger.service.ConnectionService
import kotlinx.coroutines.launch

/** Contacts tab: local address book, search, create, rename, and delete. */
class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ContactsAdapter
    private var allContacts: List<Contact> = emptyList()
    private var activeId: String? = null

    private val container get() = (requireActivity() as HomeActivity).container

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ContactsAdapter(onOpen = ::openDetail, onMore = ::showMenu)
        binding.listContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.listContacts.adapter = adapter

        binding.fabAddContact.setOnClickListener { addContact() }
        binding.buttonEmptyAdd.setOnClickListener { addContact() }
        binding.inputSearch.doAfterTextChanged { render() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.repository.contacts.collect { contacts ->
                        allContacts = contacts
                        render()
                    }
                }
                launch {
                    container.repository.activeContact.collect { contact ->
                        activeId = contact?.id
                        render()
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
        binding.listContacts.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val query = binding.inputSearch.text?.toString().orEmpty()
        val filtered = allContacts
            .filter { ContactNames.matches(it, query) }
            .sortedBy { it.name.lowercase() }

        adapter.activeContactId = activeId
        adapter.submitList(filtered)

        binding.listContacts.isVisible = filtered.isNotEmpty()
        binding.emptyContacts.isVisible = filtered.isEmpty()
        binding.textContactCount.isVisible = filtered.isNotEmpty()

        when {
            allContacts.isEmpty() -> {
                binding.textEmptyTitle.setText(R.string.contacts_empty_title)
                binding.textEmptyBody.setText(R.string.contacts_empty_body)
                binding.textEmptyBody.isVisible = true
                binding.buttonEmptyAdd.isVisible = true
                binding.textContactCount.text = ""
            }

            filtered.isEmpty() -> {
                binding.textEmptyTitle.setText(R.string.contacts_no_results)
                binding.textEmptyBody.isVisible = false
                binding.buttonEmptyAdd.isVisible = false
                binding.textContactCount.text = ""
            }

            else -> {
                binding.textContactCount.text = resources.getQuantityString(
                    R.plurals.contacts_found,
                    filtered.size,
                    filtered.size
                )
            }
        }
    }

    private fun addContact() {
        if (!isAdded) return
        ContactDialogs.show(
            context = requireContext(),
            title = R.string.contacts_add_title,
            message = R.string.contacts_add_body,
            positive = R.string.contacts_add
        ) { name, note ->
            viewLifecycleOwner.lifecycleScope.launch {
                val contact = container.repository.addContact(name, note)
                Toast.makeText(requireContext(), R.string.contacts_added, Toast.LENGTH_SHORT).show()
                openDetail(contact)
            }
        }
    }

    private fun openDetail(contact: Contact) {
        startActivity(
            Intent(requireContext(), ContactDetailActivity::class.java)
                .putExtra(ContactDetailActivity.EXTRA_CONTACT_ID, contact.id)
        )
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

    private fun showMenu(anchor: View, contact: Contact) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_INFO, 0, R.string.chats_open_info)
        if (contact.isOnKnot) popup.menu.add(0, MENU_CHAT, 1, R.string.contact_open_chat)
        popup.menu.add(0, MENU_RENAME, 2, R.string.contacts_rename_title)
        popup.menu.add(0, MENU_DELETE, 3, R.string.contact_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_INFO -> openDetail(contact)
                MENU_CHAT -> openChat(contact)
                MENU_RENAME -> rename(contact)
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
            title = R.string.contacts_rename_title,
            initialName = contact.label ?: contact.displayName,
            initialNote = contact.note,
            showNote = false,
            positive = R.string.save
        ) { name, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                container.repository.updateContact(contact.id, name, contact.note)
                Toast.makeText(requireContext(), R.string.contacts_renamed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(contact: Contact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.contact_delete_title)
            .setMessage(R.string.contact_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.contact_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.engine.deleteContact(contact.id)
                    ConnectionService.sync(requireContext())
                    Toast.makeText(requireContext(), R.string.chats_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    companion object {
        private const val MENU_INFO = 201
        private const val MENU_CHAT = 202
        private const val MENU_RENAME = 203
        private const val MENU_DELETE = 204
    }
}
