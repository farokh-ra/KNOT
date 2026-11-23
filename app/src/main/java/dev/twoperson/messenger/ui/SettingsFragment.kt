package dev.twoperson.messenger.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dev.twoperson.messenger.App
import dev.twoperson.messenger.BuildConfig
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.AppLanguage
import dev.twoperson.messenger.data.AppSettings
import dev.twoperson.messenger.data.ThemeMode
import dev.twoperson.messenger.databinding.FragmentSettingsBinding
import dev.twoperson.messenger.service.ConnectionService
import dev.twoperson.messenger.service.MessageNotifications
import dev.twoperson.messenger.util.ThemeController
import kotlinx.coroutines.launch

/**
 * Settings tab: four short sections behind tabs. Every row is a single line with its current
 * value on the right; the longer explanations live in the documentation, not in the screen.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity() as HomeActivity).container
    private var rendering = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            renderNotificationStatus()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.textVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        showSection(0)
        binding.settingsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showSection(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.buttonNotificationAction.setOnClickListener { handleNotificationAction() }
        binding.switchPreviews.setOnCheckedChangeListener { _, checked ->
            if (!rendering) container.settings.setNotificationPreviews(checked)
        }
        binding.rowPreviews.setOnClickListener { binding.switchPreviews.toggle() }
        binding.rowBackground.setOnClickListener { openBatterySettings() }
        binding.switchScreenSecurity.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            container.settings.setScreenSecurity(checked)
            applyScreenSecurityFlag(checked)
        }
        binding.rowScreenSecurity.setOnClickListener { binding.switchScreenSecurity.toggle() }
        binding.rowClearAll.setOnClickListener { confirmClearAll() }
        binding.rowDeleteAll.setOnClickListener { confirmDeleteAll() }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settings.settings.collect { renderSettings(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderNotificationStatus()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun showSection(index: Int) {
        val binding = _binding ?: return
        binding.sectionGeneral.isVisible = index == 0
        binding.sectionChats.isVisible = index == 1
        binding.sectionPrivacy.isVisible = index == 2
        binding.sectionAbout.isVisible = index == 3
    }

    private fun renderSettings(settings: AppSettings) {
        rendering = true
        binding.textThemeValue.setText(
            when (settings.theme) {
                ThemeMode.SYSTEM -> R.string.settings_theme_system_short
                ThemeMode.LIGHT -> R.string.settings_theme_light
                ThemeMode.DARK -> R.string.settings_theme_dark
            }
        )
        binding.textLanguageValue.setText(
            when (settings.language) {
                AppLanguage.SYSTEM -> R.string.settings_language_system_short
                AppLanguage.ENGLISH -> R.string.settings_language_english
                AppLanguage.PERSIAN -> R.string.settings_language_farsi
            }
        )
        binding.switchPreviews.isChecked = settings.notificationPreviews
        binding.switchScreenSecurity.isChecked = settings.screenSecurity
        rendering = false
        renderNotificationStatus()
    }

    private fun showLanguageDialog() {
        val languages = AppLanguage.entries
        val labels = arrayOf(
            getString(R.string.settings_language_system),
            getString(R.string.settings_language_english),
            getString(R.string.settings_language_farsi)
        )
        val current = languages.indexOf(container.settings.current.language).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language_dialog)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val language = languages.getOrElse(which) { AppLanguage.SYSTEM }
                container.settings.setLanguage(language)
                App.applyLanguage(language)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val modes = ThemeMode.entries
        val labels = arrayOf(
            getString(R.string.settings_theme_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        val current = modes.indexOf(container.settings.current.theme).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme_dialog)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val mode = modes.getOrElse(which) { ThemeMode.SYSTEM }
                container.settings.setTheme(mode)
                ThemeController.apply(mode)
                Toast.makeText(
                    requireContext(),
                    R.string.settings_theme_updated,
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyScreenSecurityFlag(enabled: Boolean) {
        val window = requireActivity().window
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun renderNotificationStatus() {
        if (!isAdded || _binding == null) return
        val canPost = MessageNotifications.canPost(requireContext())
        binding.textNotificationStatus.setText(
            if (canPost) R.string.settings_notification_on_short
            else R.string.settings_notification_off_short
        )
        binding.buttonNotificationAction.setText(
            when {
                canPost -> R.string.settings_notification_settings
                MessageNotifications.shouldAskPermission(requireContext()) ->
                    R.string.settings_notification_enable

                else -> R.string.settings_notification_settings
            }
        )
    }

    private fun handleNotificationAction() {
        if (MessageNotifications.canPost(requireContext())) {
            openNotificationSettings()
            return
        }
        if (MessageNotifications.shouldAskPermission(requireContext())) {
            MessageNotifications.markPermissionAsked(requireContext())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        try {
            startActivity(direct)
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${requireContext().packageName}"))
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.generic_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openBatterySettings() {
        val packageUri = Uri.parse("package:${requireContext().packageName}")
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Try the next system screen; OEMs rename or hide some of them.
            }
        }
        Toast.makeText(requireContext(), R.string.generic_error, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_clear_all)
            .setMessage(R.string.settings_clear_all_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_clear_all) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.engine.clearAllConversations()
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_clear_all_done,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun confirmDeleteAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_delete_all_confirm_title)
            .setMessage(R.string.settings_delete_all_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_delete_all_confirm_ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.engine.deleteAllContacts()
                    MessageNotifications.dismissIncoming(requireContext())
                    container.settings.reset()
                    ConnectionService.stop(requireContext())
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_delete_all_done,
                        Toast.LENGTH_LONG
                    ).show()
                    ThemeController.apply(ThemeMode.SYSTEM)
                    App.applyLanguage(AppLanguage.SYSTEM)
                }
            }
            .show()
    }
}
