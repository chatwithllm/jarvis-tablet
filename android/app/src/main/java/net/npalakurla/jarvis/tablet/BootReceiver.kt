package net.npalakurla.jarvis.tablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * FR-02 / FR-08: after boot (and after package replace) start the HUD
 * and the wake service with no touch, so a cold reboot is back to an
 * immersive Jarvis + listening wake service within 60 s.
 *
 * Note: starting the activity from BOOT_COMPLETED relies on the tablet
 * treating Jarvis as its Home/launcher (the manifest declares the HOME
 * category). The wake service start is not subject to that limit and
 * always runs; if the activity cannot auto-start, the pinned
 * notification re-opens it with one tap.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        runCatching {
            WakeService.start(context)
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
