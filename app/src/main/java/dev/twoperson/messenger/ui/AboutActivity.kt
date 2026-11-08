package dev.twoperson.messenger.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.twoperson.messenger.BuildConfig
import dev.twoperson.messenger.R
import dev.twoperson.messenger.databinding.ActivityAboutBinding

/** Static, professional About screen with the author and project links. */
class AboutActivity : KnotActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarAbout)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.textVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        binding.rowGithub.setOnClickListener { openUrl(GITHUB_URL) }
        binding.rowTelegram.setOnClickListener { openUrl(TELEGRAM_URL) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.generic_error, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.generic_error, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val GITHUB_URL = "https://github.com/farokh-ra/KNOT"
        private const val TELEGRAM_URL = "https://t.me/Farokhrex"
    }
}
