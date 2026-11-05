package net.npalakurla.jarvis.tablet

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run HA setup + FR-54 re-auth prompt.
 *
 * Collects the Home Assistant URL and the long-lived token for the
 * dedicated non-admin user `jarvis-tablet` (Phase 0 item 5) and stores
 * them in Keystore-backed EncryptedSharedPreferences via [TokenStore].
 * The token is never shown in the HUD, never logged, and never leaves
 * the device. Voice stays disabled until valid credentials are saved.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val urlInput = EditText(this).apply {
            hint = "Home Assistant URL (e.g. http://192.168.20.11:8123)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(TokenStore.getHaUrl(this@SettingsActivity) ?: "http://192.168.20.11:8123")
        }
        val tokenInput = EditText(this).apply {
            hint = "Long-lived access token (jarvis-tablet user)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = Button(this).apply {
            text = "Save and start Jarvis"
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                val token = tokenInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this@SettingsActivity, "URL must start with http:// or https://", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (token.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "Paste the jarvis-tablet token from Home Assistant", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                TokenStore.save(this@SettingsActivity, url, token)
                startActivity(
                    Intent(this@SettingsActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                finish()
            }
        }
        val note = TextView(this).apply {
            text = "The token is stored encrypted on this tablet only. " +
                "Create it in Home Assistant under the jarvis-tablet user " +
                "(non-admin, revocable). It never goes in the WebView or the repo."
            gravity = Gravity.CENTER
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(96, 96, 96, 96)
            addView(TextView(context).apply { text = "Jarvis — connect Home Assistant"; textSize = 24f; gravity = Gravity.CENTER })
            addView(urlInput)
            addView(tokenInput)
            addView(save)
            addView(note)
        }
        setContentView(layout)
    }
}
