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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.twoperson.messenger.BuildConfig
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.AppSettings
import dev.twoperson.messenger.data.ThemeMode
import dev.twoperson.messenger.databinding.FragmentSettingsBinding
import dev.twoperson.messenger.service.ConnectionService
import dev.twoperson.messenger.service.MessageNotifications
import dev.twoperson.messenger.util.ThemeController
import kotlinx.coroutines.launch

/** Settings tab: local appearance, notification and data controls. */
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

        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.buttonNotificationAction.setOnClickListener { handleNotificationAction() }
        binding.switchPreviews.setOnCheckedChangeListener { _, checked ->
            if (!rendering) container.settings.setNotificationPreviews(checked)
        }
        binding.rowPreviews.setOnClickListener { binding.switchPreviews.toggle() }
        binding.switchBackground.setOnCheckedChangeListener { _, checked ->
            if (!rendering) requestBackground(checked)
        }
        binding.rowBackground.setOnClickListener { binding.switchBackground.toggle() }
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

    private fun renderSettings(settings: AppSettings) {
        rendering = true
        binding.textThemeValue.setText(
            when (settings.theme) {
                ThemeMode.SYSTEM -> R.string.settings_theme_system
                ThemeMode.LIGHT -> R.string.settings_theme_light
                ThemeMode.DARK -> R.string.settings_theme_dark
            }
        )
        binding.switchPreviews.isChecked = settings.notificationPreviews
        binding.switchBackground.isChecked = settings.backgroundConnection
        rendering = false
        renderNotificationStatus()
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
                Toast.makeText(requireContext(), R.string.settings_theme_updated, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderNotificationStatus() {
        if (!isAdded) return
        val canPost = MessageNotifications.canPost(requireContext())
        binding.textNotificationStatus.setText(
            if (canPost) R.string.settings_notification_on else R.string.settings_notification_off
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

    private fun requestBackground(enabled: Boolean) {
        if (!enabled) {
            container.settings.setBackgroundConnection(false)
            ConnectionService.sync(requireContext())
            return
        }
        if (!container.engine.isConfigured()) {
            Toast.makeText(requireContext(), R.string.settings_no_contacts, Toast.LENGTH_SHORT).show()
            setBackgroundSwitch(false)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_background_confirm_title)
            .setMessage(R.string.settings_background_confirm_message)
            .setNegativeButton(R.string.cancel) { _, _ -> setBackgroundSwitch(false) }
            .setPositiveButton(R.string.settings_background_confirm_ok) { _, _ ->
                container.settings.setBackgroundConnection(true)
                ConnectionService.sync(requireContext())
            }
            .setOnCancelListener { setBackgroundSwitch(false) }
            .show()
    }

    private fun setBackgroundSwitch(checked: Boolean) {
        rendering = true
        binding.switchBackground.isChecked = checked
        rendering = false
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_clear_all)
            .setMessage(R.string.settings_clear_all_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_clear_all) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.engine.clearAllConversations()
                    Toast.makeText(requireContext(), R.string.settings_clear_all_done, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(requireContext(), R.string.settings_delete_all_done, Toast.LENGTH_LONG).show()
                    ThemeController.apply(ThemeMode.SYSTEM)
                }
            }
            .show()
    }
}
