package com.byyako.dkiosk.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.IOException

/** Plays one sound from a URL at a time, like a doorbell chime sent by a home automation system. */
class SoundPlayer {

    private var player: MediaPlayer? = null

    val isPlaying: Boolean
        get() = player != null

    fun play(url: String) {
        stop()
        val next = MediaPlayer()
        player = next
        next.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        next.setOnPreparedListener { it.start() }
        next.setOnCompletionListener { release(it) }
        next.setOnErrorListener { mp, what, extra ->
            Log.w(TAG, "Couldn't play $url ($what/$extra)")
            release(mp)
            true
        }
        try {
            next.setDataSource(url)
            next.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't open $url", e)
            release(next)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Couldn't open $url", e)
            release(next)
        }
    }

    fun stop() {
        player?.let(::release)
    }

    private fun release(mp: MediaPlayer) {
        if (player === mp) player = null
        mp.release()
    }

    private companion object {
        const val TAG = "SoundPlayer"
    }
}
