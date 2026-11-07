package net.npalakurla.jarvis.tablet

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run ContextForge setup + FR-54 re-auth prompt.
 *
 * Collects the ContextForge MCP gateway URL and the scoped MCP bearer,
 * plus the separate `cf-jarvis-tablet-llm` bearer, base URL, and model
 * for the direct `/llm/v1` voice path. All of it is stored in
 * Keystore-backed EncryptedSharedPreferences via [TokenStore]. The
 * tablet never holds a raw Home Assistant long-lived token. Bearers are
 * never shown in the HUD, never logged, and never leave the device.
 *
 * Voice uses the direct path when the LLM bearer is saved, and the Mini
 * relay otherwise (and when the direct call fails).
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val urlInput = field("ContextForge URL (MCP endpoint)", R.id.field_cf_url).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(TokenStore.getCfUrl(this@SettingsActivity) ?: TokenStore.DEFAULT_CF_URL)
        }
        val tokenSaved = TokenStore.getCfToken(this) != null
        val tokenInput = secretField(
            if (tokenSaved) "ContextForge MCP bearer — saved" else "ContextForge MCP bearer (cf-jarvis-tablet)",
            R.id.field_cf_token,
        ).apply {
            if (tokenSaved) setText(TokenStore.SAVED_TOKEN_MASK)
        }
        val relayInput = field("Voice relay URL (Mini) — optional, used as fallback", R.id.field_relay_url).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(TokenStore.getVoiceRelayUrl(this@SettingsActivity))
        }
        val llmUrlInput = field("LLM base URL (ContextForge /llm/v1)", R.id.field_llm_url).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(TokenStore.getLlmBaseUrl(this@SettingsActivity))
        }
        val llmModelInput = field("LLM model (default grok-4.7)", R.id.field_llm_model).apply {
            setText(TokenStore.getLlmModel(this@SettingsActivity))
        }
        val llmSaved = TokenStore.hasLlmCredentials(this)
        val llmTokenInput = secretField(
            if (llmSaved) "LLM bearer — saved" else "LLM bearer (cf-jarvis-tablet-llm)",
            R.id.field_llm_token,
        ).apply {
            if (llmSaved) setText(TokenStore.SAVED_TOKEN_MASK)
        }
        val llmState = TextView(this).apply {
            textSize = 16f
            setPadding(0, 24, 0, 12)
        }
        fun refreshLlmState() {
            llmState.text = if (TokenStore.hasLlmCredentials(this)) {
                "LLM token: saved. Voice tries ContextForge directly; the Mini relay is the fallback."
            } else {
                "LLM token: not set. Voice uses the Mini relay."
            }
        }
        refreshLlmState()
        val catalog = WakePhrases.load(this)
        val wakeNow = WakeConfig.current(this, catalog)
        val phraseBoxes = catalog.choices.map { phrase ->
            CheckBox(this).apply {
                text = phrase
                isChecked = phrase in wakeNow.enabled
                textSize = 18f
            }
        }
        val confInput = field("Wake confidence 0.50–0.99 (default 0.85)", R.id.field_wake_confidence).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(java.util.Locale.US, "%.2f", wakeNow.threshold))
        }
        val clearLlm = Button(this).apply {
            text = "Clear LLM token (use Mini relay)"
            setOnClickListener {
                TokenStore.clearLlmToken(this@SettingsActivity)
                llmTokenInput.setText("")
                refreshLlmState()
                Toast.makeText(
                    this@SettingsActivity,
                    "LLM token cleared. Voice will use the Mini relay.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        val save = Button(this).apply {
            text = "Save and start Jarvis"
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                val token = tokenInput.text.toString().trim()
                val relay = relayInput.text.toString().trim()
                val llmUrl = llmUrlInput.text.toString().trim()
                val llmModel = llmModelInput.text.toString().trim()
                val llmToken = llmTokenInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast("URL must start with http:// or https://")
                    return@setOnClickListener
                }
                val keepMcp = token.isEmpty() || token.equals(TokenStore.SAVED_TOKEN_MASK, ignoreCase = true)
                if (keepMcp && TokenStore.getCfToken(this@SettingsActivity) == null) {
                    toast("Paste the tablet MCP bearer issued by IT Admin")
                    return@setOnClickListener
                }
                if (relay.isNotEmpty() && !relay.startsWith("http://") && !relay.startsWith("https://")) {
                    toast("Voice relay URL must start with http:// or https://")
                    return@setOnClickListener
                }
                if (llmUrl.isNotEmpty() && !llmUrl.startsWith("http://") && !llmUrl.startsWith("https://")) {
                    toast("LLM URL must start with http:// or https://")
                    return@setOnClickListener
                }
                if (llmModel.any { it.isWhitespace() }) {
                    toast("LLM model cannot contain spaces")
                    return@setOnClickListener
                }
                val enabledPhrases = phraseBoxes.filter { it.isChecked }.map { it.text.toString() }.toSet()
                if (enabledPhrases.isEmpty()) {
                    toast("Leave at least one wake phrase on. Hey Jarvis is not available on this tablet.")
                    return@setOnClickListener
                }
                val wakeConf = confInput.text.toString().toFloatOrNull()
                if (wakeConf == null || wakeConf < 0.50f || wakeConf > 0.99f) {
                    toast("Wake confidence must be between 0.50 and 0.99")
                    return@setOnClickListener
                }
                TokenStore.save(this@SettingsActivity, url, token)
                if (relay.isNotEmpty()) TokenStore.saveVoiceRelayUrl(this@SettingsActivity, relay)
                TokenStore.saveLlm(
                    this@SettingsActivity,
                    llmUrl.ifEmpty { TokenStore.DEFAULT_LLM_URL },
                    llmToken,
                    llmModel.ifEmpty { TokenStore.DEFAULT_LLM_MODEL },
                )
                WakeConfig.save(this@SettingsActivity, enabledPhrases, wakeConf)
                startActivity(
                    Intent(this@SettingsActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                finish()
            }
        }
        val note = TextView(this).apply {
            text = "Both bearers are stored encrypted on this tablet only. " +
                "The MCP bearer (cf-jarvis-tablet) is for Home Assistant reads through " +
                "ContextForge. The LLM bearer (cf-jarvis-tablet-llm) is a different token — " +
                "the MCP one is rejected on /llm/v1. No raw Home Assistant token is ever " +
                "typed here or stored on the tablet. Nothing here goes in the WebView or the repo. " +
                "A field that says saved already has a token — leave it to keep that token. " +
                "Clear the LLM bearer to force the Mini relay."
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(96, 48, 96, 48)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(TextView(context).apply {
                text = "Jarvis — connect ContextForge"
                textSize = 24f
                gravity = Gravity.CENTER
            })
            addView(urlInput)
            addView(tokenInput)
            addView(relayInput)
            addView(llmUrlInput)
            addView(llmModelInput)
            addView(llmTokenInput)
            addView(llmState)
            addView(clearLlm)
            addView(TextView(context).apply {
                text = "Wake phrases (hey jarvis is never armed)"
                textSize = 18f
                setPadding(0, 28, 0, 0)
            })
            phraseBoxes.forEach { addView(it) }
            addView(confInput)
            addView(save)
            addView(note)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun field(hintText: String, fieldId: Int): EditText = EditText(this).apply {
        id = fieldId
        contentDescription = resources.getResourceEntryName(fieldId)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 20 }
    }

    private fun secretField(hintText: String, fieldId: Int): EditText = field(hintText, fieldId).apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
