package dev.twoperson.messenger.ui

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.twoperson.messenger.R
import dev.twoperson.messenger.databinding.DialogContactBinding

/**
 * One dialog for creating, renaming and editing a contact. The name and private note are
 * sanitized by the repository; filters here only stop the user before the limit is reached.
 */
object ContactDialogs {

    fun show(
        context: Context,
        @StringRes title: Int,
        @StringRes message: Int? = null,
        initialName: String? = null,
        initialNote: String? = null,
        showNote: Boolean = true,
        @StringRes positive: Int = R.string.save,
        onSave: (name: String?, note: String?) -> Unit
    ) {
        val binding = DialogContactBinding.inflate(LayoutInflater.from(context))
        binding.inputName.setText(initialName.orEmpty())
        binding.inputNote.setText(initialNote.orEmpty())
        binding.inputNoteLayout.visibility = if (showNote) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        binding.inputName.setSelection(binding.inputName.text?.length ?: 0)

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positive) { _, _ ->
                onSave(
                    binding.inputName.text?.toString(),
                    if (showNote) binding.inputNote.text?.toString() else initialNote
                )
            }
        if (message != null) builder.setMessage(message)
        builder.show()
    }
}
