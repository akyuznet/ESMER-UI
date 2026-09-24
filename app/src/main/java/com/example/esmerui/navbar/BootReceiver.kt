package com.example.esmerui.navbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the bars up when the head unit boots.
 *
 * The bar service used to start only from the home screen, which meant a boot
 * that landed somewhere else - the car reconnecting Android Auto, another app
 * restoring itself - left BYD's own bars on screen and ours nowhere. The unit
 * restarts every time the car does, so this is the common case, not the edge.
 *
 * [NavBarService.sync] already knows whether any bar is configured and does
 * nothing when none is, so there is nothing to decide here.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "boot: ${intent.action}")
        NavBarService.sync(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
