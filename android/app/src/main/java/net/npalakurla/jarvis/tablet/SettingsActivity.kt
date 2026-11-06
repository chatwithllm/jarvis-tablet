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
 * First-run ContextForge setup + FR-54 re-auth prompt.
 *
 * Collects the ContextForge MCP gateway URL and the scoped `jarvis-ha`
 * bearer (issue #10, owner direction 2026-11-05) and stores them in
 * Keystore-backed EncryptedSharedPreferences via [TokenStore]. The
 * tablet never holds a raw Home Assistant long-lived token — the
 * upstream HA credential lives in the gateway DB. The bearer is never
 * shown in the HUD, never logged, and never leaves the device. Voice
 * stays disabled until valid credentials are saved.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val urlInput = EditText(this).apply {
            hint = "ContextForge URL (MCP endpoint)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(TokenStore.getCfUrl(this@SettingsActivity) ?: TokenStore.DEFAULT_CF_URL)
        }
        val tokenInput = EditText(this).apply {
            hint = "ContextForge bearer token (jarvis-ha)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val relayInput = EditText(this).apply {
            hint = "Voice relay URL (Mini, e.g. http://192.168.20.x:8792) — optional"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(TokenStore.getVoiceRelayUrl(this@SettingsActivity) ?: "")
        }
        val save = Button(this).apply {
            text = "Save and start Jarvis"
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                val token = tokenInput.text.toString().trim()
                val relay = relayInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this@SettingsActivity, "URL must start with http:// or https://", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (token.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "Paste the jarvis-ha bearer token issued by IT Admin", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (relay.isNotEmpty() && !relay.startsWith("http://") && !relay.startsWith("https://")) {
                    Toast.makeText(this@SettingsActivity, "Voice relay URL must start with http:// or https://", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                TokenStore.save(this@SettingsActivity, url, token)
                if (relay.isNotEmpty()) TokenStore.saveVoiceRelayUrl(this@SettingsActivity, relay)
                startActivity(
                    Intent(this@SettingsActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                finish()
            }
        }
        val note = TextView(this).apply {
            text = "The bearer is stored encrypted on this tablet only. " +
                "It is issued by IT Admin for this tablet (jarvis-ha scope). " +
                "The Home Assistant credential stays in the gateway — " +
                "no raw HA token is ever typed here or stored on the tablet. " +
                "It never goes in the WebView or the repo."
            gravity = Gravity.CENTER
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(96, 96, 96, 96)
            addView(TextView(context).apply { text = "Jarvis — connect ContextForge"; textSize = 24f; gravity = Gravity.CENTER })
            addView(urlInput)
            addView(tokenInput)
            addView(relayInput)
            addView(save)
            addView(note)
        }
        setContentView(layout)
    }
}
