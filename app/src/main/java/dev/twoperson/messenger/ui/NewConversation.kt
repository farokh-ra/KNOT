package dev.twoperson.messenger.ui

import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.twoperson.messenger.R
import dev.twoperson.messenger.databinding.BottomSheetNewConversationBinding
import kotlinx.coroutines.launch

/**
 * The one entry point used by every "new conversation" button. The user first chooses whether
 * to create an invitation (show a QR code) or join someone else's code, then names the contact.
 * Keeping this in one place means the action is identical on Chats, Contacts and both empty
 * states instead of drifting apart.
 */
object NewConversation {

    fun show(fragment: Fragment, join: Boolean = false) {
        if (!fragment.isAdded) return
        val activity = fragment.requireActivity()
        val dialog = BottomSheetDialog(activity)
        val binding = BottomSheetNewConversationBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)
        binding.optionCreate.setOnClickListener {
            dialog.dismiss()
            start(fragment, join = false)
        }
        binding.optionJoin.setOnClickListener {
            dialog.dismiss()
            start(fragment, join = true)
        }
        binding.buttonCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
        if (join) binding.optionJoin.requestFocus()
    }

    private fun start(fragment: Fragment, join: Boolean) {
        if (!fragment.isAdded) return
        ContactDialogs.show(
            context = fragment.requireContext(),
            title = R.string.chats_new_contact_title,
            message = if (join) {
                R.string.chats_join_code_body
            } else {
                R.string.chats_new_contact_body
            },
            positive = R.string.chats_new_contact_create
        ) { name, note ->
            if (!fragment.isAdded) return@show
            val container = (fragment.requireActivity() as HomeActivity).container
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val contact = container.repository.addContact(name, note)
                container.engine.selectContact(contact.id)
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.chats_new_contact_added,
                    Toast.LENGTH_SHORT
                ).show()
                fragment.startActivity(
                    Intent(fragment.requireContext(), PairingActivity::class.java)
                        .putExtra(PairingActivity.EXTRA_CONTACT_ID, contact.id)
                        .putExtra(
                            PairingActivity.EXTRA_MODE,
                            if (join) PairingActivity.MODE_JOIN else PairingActivity.MODE_CREATE
                        )
                )
            }
        }
    }
}
