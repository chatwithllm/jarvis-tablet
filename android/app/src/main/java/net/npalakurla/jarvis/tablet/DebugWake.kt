package net.npalakurla.jarvis.tablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.FileInputStream

/**
 * Debug-only inject of a 16 kHz mono WAV into the live wake pipeline.
 *
 * Registered by [WakeService] when [BuildConfig.DEBUG] is true, so
 * `adb shell am broadcast -a net.npalakurla.jarvis.tablet.DEBUG_WAKE_WAV
 * --es path /sdcard/Download/x.wav` reaches the running process.
 * Release builds do not register it.
 *
 * Logcat (tag JarvisWake): `wake_detected phrase=... conf=...` or `wake_none`.
 */
class DebugWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != ACTION) return
        val path = intent.getStringExtra("path")
        val pending = goAsync()
        Thread({
            try {
                WakeService.debugWav(context.applicationContext, path)
            } finally {
                pending.finish()
            }
        }, "jarvis-debug-wav").start()
    }

    companion object {
        const val ACTION = "net.npalakurla.jarvis.tablet.DEBUG_WAKE_WAV"
    }
}

object DebugWake {
    fun run(context: Context, path: String?) {
        if (path.isNullOrBlank()) {
            WakeLog.wavError("missing path")
            WakeLog.none()
            return
        }
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            WakeLog.wavError("unreadable $path")
            WakeLog.none()
            return
        }
        val pcm = try {
            decodeWav(file)
        } catch (e: Exception) {
            WakeLog.wavError(e.message ?: e.javaClass.simpleName)
            WakeLog.none()
            return
        }
        if (pcm == null) {
            WakeLog.wavError("need 16 kHz mono 16-bit wav")
            WakeLog.none()
            return
        }
        val hit = VoskWakeEngine.runPcm(context, pcm)
        if (hit == null) WakeLog.none() else WakeLog.detected(hit.phrase, hit.conf)
    }

    /** PCM samples, or null when the WAV is not 16 kHz mono 16-bit. */
    internal fun decodeWav(file: File): ShortArray? {
        FileInputStream(file).use { input ->
            val header = ByteArray(12)
            if (input.read(header) != 12) return null
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var pcm: ByteArray? = null
            val chunk = ByteArray(8)
            while (input.read(chunk) == 8) {
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = (chunk[4].toInt() and 0xFF) or
                    ((chunk[5].toInt() and 0xFF) shl 8) or
                    ((chunk[6].toInt() and 0xFF) shl 16) or
                    ((chunk[7].toInt() and 0xFF) shl 24)
                if (size < 0 || size > 64 * 1024 * 1024) return null
                val body = ByteArray(size)
                var got = 0
                while (got < size) {
                    val n = input.read(body, got, size - got)
                    if (n < 0) return null
                    got += n
                }
                if (id == "fmt " && size >= 16) {
                    val format = (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8)
                    channels = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
                    sampleRate = (body[4].toInt() and 0xFF) or
                        ((body[5].toInt() and 0xFF) shl 8) or
                        ((body[6].toInt() and 0xFF) shl 16) or
                        ((body[7].toInt() and 0xFF) shl 24)
                    bits = (body[14].toInt() and 0xFF) or ((body[15].toInt() and 0xFF) shl 8)
                    if (format != 1) return null
                } else if (id == "data") {
                    pcm = body
                }
                if (size % 2 == 1) input.read()
            }
            val data = pcm ?: return null
            if (sampleRate != 16_000 || channels != 1 || bits != 16) return null
            val samples = ShortArray(data.size / 2)
            var i = 0
            var s = 0
            while (i + 1 < data.size) {
                val lo = data[i].toInt() and 0xFF
                val hi = data[i + 1].toInt()
                samples[s++] = ((hi shl 8) or lo).toShort()
                i += 2
            }
            return samples
        }
    }
}
