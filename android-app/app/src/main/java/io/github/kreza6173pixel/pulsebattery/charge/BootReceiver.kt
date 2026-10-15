package io.github.kreza6173pixel.pulsebattery.charge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Decides what happens to the charge limit after a reboot.
 *
 * Two separate concerns, which used to be tangled together:
 *
 * 1. The user's choice, [ChargeLimitSettings.restoreOnBoot]: should the limit keep enforcing?
 * 2. Safety: the charge gate survives a reboot on some kernels, so a phone that rebooted
 *    while charging was held would stay plugged in without charging, forever.
 *
 * Both are honoured. With the switch on, the limit resumes as before. With it off, the limit
 * is turned off in storage so the UI cannot show an armed limit that nothing enforces, and
 * the service runs one release pass to reopen the gate and then stops.
 *
 * With neither the limit nor the switch in use, nothing starts at all: installing this app
 * does not make it an always-on component.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = ChargeLimitStore(context)
        val settings = store.read()

        if (settings.restoreOnBoot) {
            ChargeLimitService.start(context)
            return
        }
        if (!settings.enabled) return

        // The limit was on but the user did not ask for it to survive a reboot. Write that
        // down before starting anything, so the screen and the behaviour agree.
        store.write(settings.copy(enabled = false))
        ChargeLimitService.start(context, releaseOnly = true)
    }
}
