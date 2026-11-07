package net.npalakurla.jarvis.tablet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Phase 2 — always-on Hey Jack foreground service (PRD v2.2 FR-06/20/29/32).
 *
 * Foreground service, pinned non-dismissible notification, START_STICKY.
 * Reads 16 kHz mic audio in 80 ms frames, meters the level (~12.5 Hz,
 * close to the bridge's ~15 Hz), and — once the trained hey_jack.onnx
 * lands in Phase 2 — scores each frame for the wake word.
 *
 * Until the model exists the service still runs (levels + heartbeat);
 * the turn fails gracefully when HA is down (FR-32) and no cloud
 * session opens while ambient. Mute-after-wake tail is configurable,
 * 0 ms default (FR-29) so chained commands are not clipped.
 */
class WakeService : Service() {

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val micLock = Any()
    @Volatile private var micYielded = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis listening")
                .setContentText("Hey Jack is on — tap the orb or say Hey Jack")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build(),
        )
        isRunning = true
        instance = this
        startMicLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: the system restarts the service after a kill,
        // and BootReceiver restarts it after reboot (FR-02/FR-08).
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(micLock) {
            running = false
            micYielded = false
            val rec = recorder
            // stop() unblocks AudioRecord.read so the worker can exit.
            runCatching { rec?.stop() }
            worker?.join(500)
            runCatching { rec?.release() }
            recorder = null
            worker = null
            isRunning = false
            if (instance === this) instance = null
        }
        super.onDestroy()
    }

    /**
     * Release the microphone so [DeviceSpeech] can bind the platform
     * recognizer. The wake loop is restarted by [resumeMicInternal].
     * Safe to call when the mic was never opened.
     */
    private fun pauseMicInternal() {
        synchronized(micLock) {
            if (recorder == null && worker == null) return
            running = false
            val rec = recorder
            runCatching { rec?.stop() }
            worker?.join(800)
            runCatching { rec?.release() }
            recorder = null
            worker = null
            micYielded = true
        }
    }

    private fun resumeMicInternal() {
        synchronized(micLock) {
            if (!micYielded) return
            micYielded = false
            if (!isRunning) return
            startMicLoop()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Jarvis wake word",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Always-on Hey Jack listening" },
        )
    }

    private fun startMicLoop() {
        synchronized(micLock) {
            startMicLoopLocked()
        }
    }

    private fun startMicLoopLocked() {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener?.sendEvent(
                "error",
                JSONObject()
                    .put("code", "mic_permission")
                    .put("message", "Microphone permission missing — wake word off."),
            )
            return
        }
        WakeModel.load(this)
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_SAMPLES * 2) * 2
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        ).also { it.startRecording() }
        running = true
        worker = Thread({
            val frame = ShortArray(FRAME_SAMPLES)
            var mutedUntilMs = 0L
            while (running) {
                val read = recorder?.read(frame, 0, FRAME_SAMPLES) ?: 0
                if (read <= 0) continue
                val level = rmsLevel(frame, read)
                listener?.sendEvent(
                    "listening_level",
                    JSONObject().put("level", level),
                )
                val now = System.currentTimeMillis()
                if (now < mutedUntilMs) continue
                if (WakeModel.state == "ready" && WakeModel.score(frame) >= threshold) {
                    mutedUntilMs = now + muteTailMs
                    listener?.sendEvent(
                        "wake_detected",
                        JSONObject().put("source", "wake"),
                    )
                }
            }
        }, "jarvis-wake").also { it.start() }
    }

    private fun rmsLevel(frame: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val v = frame[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / count).coerceIn(0.0, 1.0)
    }

    companion object {
        const val CHANNEL_ID = "jarvis_wake"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1_280 // 80 ms @ 16 kHz (PRD voice stack)

        /** Runtime tunables (Phase 2 tuning week): threshold + mute tail. */
        @Volatile var threshold: Float = 0.7f
        @Volatile var muteTailMs: Long = 0L // FR-29: 0 ms default

        @Volatile var isRunning: Boolean = false
            private set

        /** Set by MainActivity; the service only ever *sends* bridge events. */
        @Volatile var listener: JarvisBridge? = null

        @Volatile private var instance: WakeService? = null

        /** Let a voice turn use the mic. No-op when the service is not running. */
        fun pauseMic() {
            instance?.pauseMicInternal()
        }

        /** Start the wake recorder again after [pauseMic], if it was stopped. */
        fun resumeMic() {
            instance?.resumeMicInternal()
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }
    }
}
