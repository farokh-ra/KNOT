package dev.twoperson.messenger.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.twoperson.messenger.data.GifStore
import dev.twoperson.messenger.databinding.BottomSheetGifsBinding
import dev.twoperson.messenger.databinding.ItemGifBinding
import java.io.File

/** The user's GIF gallery. Every visible tile animates immediately. */
object GifPickerSheet {

    fun show(
        activity: AppCompatActivity,
        store: GifStore,
        onPick: (File) -> Unit,
        onAdd: () -> Unit
    ) {
        val binding = BottomSheetGifsBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(binding.root)

        val adapter = GifAdapter { file ->
            dialog.dismiss()
            onPick(file)
        }
        binding.listGifs.layoutManager = GridLayoutManager(activity, 3)
        binding.listGifs.adapter = adapter
        binding.buttonAddGif.setOnClickListener {
            dialog.dismiss()
            onAdd()
        }

        fun refresh() {
            val files = store.files()
            adapter.submit(files)
            binding.textGifEmpty.isVisible = files.isEmpty()
            binding.listGifs.isVisible = files.isNotEmpty()
        }
        refresh()

        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    private class GifAdapter(
        private val onPick: (File) -> Unit
    ) : RecyclerView.Adapter<GifAdapter.Holder>() {

        private var files: List<File> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<File>) {
            files = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemGifBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = files.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(files[position], onPick)
        }

        class Holder(private val binding: ItemGifBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(file: File, onPick: (File) -> Unit) {
                binding.imageGif.contentDescription = file.name
                GifLoader.load(binding.imageGif, file)
                val pick = android.view.View.OnClickListener { onPick(file) }
                binding.imageGif.setOnClickListener(pick)
                binding.root.setOnClickListener(pick)
            }
        }
    }
}
