package com.example.esmerui.car

import android.content.Context
import android.media.AudioManager

/**
 * Volume via the standard AudioManager rather than BYD's audio device.
 *
 * BYDAutoAudioDevice exists, but its useful setters sit behind the signature-only
 * BYDAUTO_AUDIO_SET permission - and the platform AudioManager needs no permission
 * at all and routes through the same media stream, so it is both simpler and more
 * likely to keep working across ROM updates.
 */
object AudioControl {

    private const val STREAM = AudioManager.STREAM_MUSIC

    private fun manager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumeUp(context: Context) = adjust(context, AudioManager.ADJUST_RAISE)

    fun volumeDown(context: Context) = adjust(context, AudioManager.ADJUST_LOWER)

    fun toggleMute(context: Context) = adjust(context, AudioManager.ADJUST_TOGGLE_MUTE)

    /** Current volume as a 0..1 fraction, for a status readout. */
    fun level(context: Context): Float = runCatching {
        val am = manager(context)
        val max = am.getStreamMaxVolume(STREAM).coerceAtLeast(1)
        am.getStreamVolume(STREAM) / max.toFloat()
    }.getOrDefault(0f)

    private fun adjust(context: Context, direction: Int): Boolean = runCatching {
        // FLAG_SHOW_UI so the driver gets the system volume panel as confirmation.
        manager(context).adjustStreamVolume(STREAM, direction, AudioManager.FLAG_SHOW_UI)
        true
    }.getOrDefault(false)
}
