package dev.twoperson.messenger.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.badge.BadgeDrawable
import dev.twoperson.messenger.R
import dev.twoperson.messenger.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch

/**
 * The main shell: one toolbar and three tabs. Chats and Contacts are ordinary fragments so
 * their lists and scroll positions survive tab switches. The selected connection follows the
 * contact the user opens, not the tab that happens to be visible.
 */
class HomeActivity : KnotActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var totalUnread = 0

    private val backToChats = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.bottomNav.selectedItemId = R.id.nav_chats
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarHome)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        val initialTab = savedInstanceState?.getInt(KEY_TAB) ?: R.id.nav_chats
        binding.bottomNav.selectedItemId = initialTab
        if (savedInstanceState == null || supportFragmentManager.findFragmentById(R.id.fragmentContainer) == null) {
            showTab(initialTab, replace = true)
        } else {
            updateTitle(initialTab)
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId, replace = true)
            true
        }
        onBackPressedDispatcher.addCallback(this, backToChats)
        backToChats.isEnabled = binding.bottomNav.selectedItemId != R.id.nav_chats

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.repository.totalUnread.collect { count ->
                    totalUnread = count
                    renderBadge()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { container.repository.refresh() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_TAB, binding.bottomNav.selectedItemId)
        super.onSaveInstanceState(outState)
    }

    private fun showTab(itemId: Int, replace: Boolean) {
        updateTitle(itemId)
        backToChats.isEnabled = itemId != R.id.nav_chats
        if (!replace) return
        val fragment: Fragment = when (itemId) {
            R.id.nav_contacts -> ContactsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> ChatsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, TAG_TAB)
            .commit()
    }

    private fun updateTitle(itemId: Int) {
        val title = when (itemId) {
            R.id.nav_contacts -> R.string.home_contacts_title
            R.id.nav_settings -> R.string.home_settings_title
            else -> R.string.home_chats_title
        }
        supportActionBar?.setTitle(title)
    }

    private fun renderBadge() {
        val badge: BadgeDrawable = binding.bottomNav.getOrCreateBadge(R.id.nav_chats)
        badge.maxCharacterCount = 3
        badge.isVisible = totalUnread > 0
        if (totalUnread > 0) badge.number = totalUnread
    }

    companion object {
        private const val KEY_TAB = "selected_tab"
        private const val TAG_TAB = "home_tab"
    }
}
