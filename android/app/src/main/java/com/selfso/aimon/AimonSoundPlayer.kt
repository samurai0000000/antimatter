/*
 * AimonSoundPlayer.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthesizes and plays the Antigravity Desktop IDE harmonic notification chimes
 * using 44.1 kHz 16-bit PCM AudioTrack streaming buffers.
 */
object AimonSoundPlayer {
    private const val TAG = "AimonSoundPlayer"
    private const val SAMPLE_RATE = 44100
    private val soundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ChordStep(
        val frequenciesHz: DoubleArray,
        val startMs: Int,
        val durationMs: Int,
        val gain: Double = 0.85
    )

    /**
     * Plays the provider-distinct prompt-answered chime:
     * - Antigravity ("cli" / "ide"): Ascending C5 -> G5 -> C6 harmonic triad progression
     * - Claude Code ("claude"): Warm D-major9 acoustic bell progression (D5-F#5-A5 -> A5-C#6-E6 -> D5-A5-D6-F#6)
     * - Cursor ("cursor"): Crisp E-minor7 -> A-major -> E-major9 synth-marimba arpeggio
     */
    fun playPromptAnsweredChime(context: Context? = null, source: String = "cli") {
        val appCtx = context?.applicationContext
        val src = source.trim().lowercase()
        soundScope.launch {
            val steps = when (src) {
                "claude" -> listOf(
                    ChordStep(doubleArrayOf(587.33, 739.99, 880.00), startMs = 0, durationMs = 180, gain = 0.82),
                    ChordStep(doubleArrayOf(880.00, 1108.73, 1318.51), startMs = 135, durationMs = 190, gain = 0.86),
                    ChordStep(doubleArrayOf(587.33, 880.00, 1174.66, 1479.98), startMs = 270, durationMs = 360, gain = 0.95)
                )
                "cursor" -> listOf(
                    ChordStep(doubleArrayOf(659.25, 783.99, 987.77), startMs = 0, durationMs = 140, gain = 0.82),
                    ChordStep(doubleArrayOf(880.00, 1108.73, 1318.51), startMs = 105, durationMs = 150, gain = 0.88),
                    ChordStep(doubleArrayOf(659.25, 987.77, 1318.51, 1661.22), startMs = 210, durationMs = 320, gain = 0.95)
                )
                else -> listOf(
                    // C5 - E5 - G5
                    ChordStep(doubleArrayOf(523.25, 659.25, 783.99), startMs = 0, durationMs = 170, gain = 0.80),
                    // G5 - B5 - D6
                    ChordStep(doubleArrayOf(783.99, 987.77, 1174.66), startMs = 125, durationMs = 180, gain = 0.85),
                    // C5 - G5 - E6 - C6 (warm resonant resolution)
                    ChordStep(doubleArrayOf(523.25, 783.99, 1046.50, 1318.51), startMs = 250, durationMs = 340, gain = 0.95)
                )
            }
            playSynthesizedSequence(appCtx, steps, totalDurationMs = 640)
        }
    }

    /**
     * Plays the provider-distinct agent-question / elicitation chime:
     * - Antigravity ("cli" / "ide"): Two-step inquisitive rising F5-A5-C6 -> A5-C6-F6 triad
     * - Claude Code ("claude"): Warm G-major7 rising bell (G5-B5-D6 -> B5-D6-F#6)
     * - Cursor ("cursor"): Crisp B5-D#6-F#6 -> E6-G#6-B6 synth elicitation chime
     */
    fun playAgentQuestionChime(context: Context? = null, source: String = "cli") {
        val appCtx = context?.applicationContext
        val src = source.trim().lowercase()
        soundScope.launch {
            val steps = when (src) {
                "claude" -> listOf(
                    ChordStep(doubleArrayOf(783.99, 987.77, 1174.66), startMs = 0, durationMs = 185, gain = 0.85),
                    ChordStep(doubleArrayOf(987.77, 1174.66, 1479.98), startMs = 155, durationMs = 330, gain = 0.95)
                )
                "cursor" -> listOf(
                    ChordStep(doubleArrayOf(987.77, 1244.51, 1479.98), startMs = 0, durationMs = 165, gain = 0.85),
                    ChordStep(doubleArrayOf(1318.51, 1661.22, 1975.53), startMs = 140, durationMs = 310, gain = 0.95)
                )
                else -> listOf(
                    // F5 - A5 - C6
                    ChordStep(doubleArrayOf(698.46, 880.00, 1046.50), startMs = 0, durationMs = 180, gain = 0.85),
                    // A5 - C6 - F6
                    ChordStep(doubleArrayOf(880.00, 1046.50, 1396.91), startMs = 150, durationMs = 320, gain = 0.95)
                )
            }
            playSynthesizedSequence(appCtx, steps, totalDurationMs = 500)
        }
    }

    private fun playSynthesizedSequence(context: Context?, steps: List<ChordStep>, totalDurationMs: Int) {
        var audioTrack: AudioTrack? = null
        try {
            val activeSamples = (SAMPLE_RATE * totalDurationMs) / 1000
            // Append 160ms of trailing silence so the hardware audio HAL flushes the full decay tail
            val tailPaddingSamples = (SAMPLE_RATE * 160) / 1000
            val totalSamples = activeSamples + tailPaddingSamples
            val mixBuffer = DoubleArray(totalSamples)

            for (step in steps) {
                val startSample = ((SAMPLE_RATE.toLong() * step.startMs) / 1000L).toInt().coerceIn(0, activeSamples)
                val stepSamples = ((SAMPLE_RATE.toLong() * step.durationMs) / 1000L).toInt().coerceAtLeast(1)
                val attackSamples = ((SAMPLE_RATE * 6) / 1000).coerceAtLeast(1)
                val perFreqGain = step.gain / step.frequenciesHz.size.coerceAtLeast(1)

                for (i in 0 until stepSamples) {
                    val outIdx = startSample + i
                    if (outIdx >= activeSamples) break

                    val t = i.toDouble() / SAMPLE_RATE.toDouble()
                    val attackEnv = if (i < attackSamples) {
                        0.5 * (1.0 - kotlin.math.cos(PI * i.toDouble() / attackSamples.toDouble()))
                    } else {
                        1.0
                    }
                    val decayEnv = exp(-2.8 * (i.toDouble() / stepSamples.toDouble()))
                    val env = attackEnv * decayEnv

                    var sampleSum = 0.0
                    for (freq in step.frequenciesHz) {
                        val fundamental = sin(2.0 * PI * freq * t)
                        val subOctave = 0.35 * sin(2.0 * PI * (freq * 0.5) * t)
                        val overtone = 0.30 * sin(2.0 * PI * (freq * 2.0) * t) * exp(-5.0 * (i.toDouble() / stepSamples.toDouble()))
                        sampleSum += (fundamental + subOctave + overtone) * perFreqGain * env
                    }
                    mixBuffer[outIdx] += sampleSum
                }
            }

            // Peak-normalize the polyphonic buffer to 0.92 full-scale so it is crisp and audible on phone speakers
            var maxAbs = 0.0
            for (i in 0 until activeSamples) {
                val a = abs(mixBuffer[i])
                if (a > maxAbs) maxAbs = a
            }
            val normScale = if (maxAbs > 1e-6) (0.92 / maxAbs) else 1.0

            val pcm = ShortArray(totalSamples)
            for (i in 0 until activeSamples) {
                val normalized = (mixBuffer[i] * normScale).coerceIn(-0.98, 0.98)
                pcm[i] = (normalized * Short.MAX_VALUE).toInt().toShort()
            }

            // Route through USAGE_MEDIA by default so the chime plays even when the phone ringer is set to Vibrate.
            // If STREAM_MUSIC volume is 0 on the device, fall back to USAGE_ALARM so an enabled in-app Sound toggle is still audible.
            var usage = AudioAttributes.USAGE_MEDIA
            var contentType = AudioAttributes.CONTENT_TYPE_MUSIC
            if (context != null) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    val mediaVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val alarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    if (mediaVol <= 0 && alarmVol > 0) {
                        usage = AudioAttributes.USAGE_ALARM
                        contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION
                    }
                }
            }

            val attrBuilder = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                attrBuilder.setHapticChannelsMuted(false)
            }

            val minBufBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attrBuilder.build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufBytes * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack.setVolume(1.0f)
            audioTrack.play()
            audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            Thread.sleep(120L)
            audioTrack.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to play synthesized notification chime: ${e.message}")
        } finally {
            try {
                audioTrack?.release()
            } catch (_: Throwable) {}
        }
    }
}
