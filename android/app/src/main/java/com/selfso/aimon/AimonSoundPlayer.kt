/*
 * AimonSoundPlayer.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthesizes and plays the Antigravity Desktop IDE harmonic notification chimes
 * using 44.1 kHz 16-bit PCM AudioTrack buffers.
 */
object AimonSoundPlayer {
    private const val TAG = "AimonSoundPlayer"
    private const val SAMPLE_RATE = 44100
    private val soundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ChordStep(
        val frequenciesHz: DoubleArray,
        val startMs: Int,
        val durationMs: Int,
        val gain: Double = 0.75
    )

    /**
     * Plays the Antigravity IDE prompt-answered chime:
     * Ascending C-major / G-major / C5 triad progression matching module 38189 in Antigravity IDE.
     */
    fun playPromptAnsweredChime() {
        soundScope.launch {
            val steps = listOf(
                // C4 - E4 - G4
                ChordStep(doubleArrayOf(261.63, 329.63, 392.00), startMs = 0, durationMs = 150, gain = 0.65),
                // G4 - B4 - D5
                ChordStep(doubleArrayOf(392.00, 493.88, 587.33), startMs = 115, durationMs = 160, gain = 0.70),
                // C5 - E5 - G5 (warm resonant resolution)
                ChordStep(doubleArrayOf(523.25, 659.25, 783.99), startMs = 230, durationMs = 280, gain = 0.80)
            )
            playSynthesizedSequence(steps, totalDurationMs = 520)
        }
    }

    /**
     * Plays the Antigravity IDE agent-question / elicitation chime:
     * Two-step inquisitive rising F-major triad (F4-A4-C5 -> A4-C5-F5).
     */
    fun playAgentQuestionChime() {
        soundScope.launch {
            val steps = listOf(
                // F4 - A4 - C5
                ChordStep(doubleArrayOf(349.23, 440.00, 523.25), startMs = 0, durationMs = 160, gain = 0.70),
                // A4 - C5 - F5
                ChordStep(doubleArrayOf(440.00, 523.25, 698.46), startMs = 135, durationMs = 270, gain = 0.82)
            )
            playSynthesizedSequence(steps, totalDurationMs = 420)
        }
    }

    private fun playSynthesizedSequence(steps: List<ChordStep>, totalDurationMs: Int) {
        try {
            val totalSamples = (SAMPLE_RATE * totalDurationMs) / 1000
            val mixBuffer = DoubleArray(totalSamples)

            for (step in steps) {
                val startSample = ((SAMPLE_RATE.toLong() * step.startMs) / 1000L).toInt().coerceIn(0, totalSamples)
                val stepSamples = ((SAMPLE_RATE.toLong() * step.durationMs) / 1000L).toInt().coerceAtLeast(1)
                val attackSamples = ((SAMPLE_RATE * 8) / 1000).coerceAtLeast(1)
                val perFreqGain = step.gain / step.frequenciesHz.size.coerceAtLeast(1)

                for (i in 0 until stepSamples) {
                    val outIdx = startSample + i
                    if (outIdx >= totalSamples) break

                    val t = i.toDouble() / SAMPLE_RATE.toDouble()
                    val attackEnv = if (i < attackSamples) {
                        0.5 * (1.0 - kotlin.math.cos(PI * i.toDouble() / attackSamples.toDouble()))
                    } else {
                        1.0
                    }
                    val decayEnv = exp(-4.8 * (i.toDouble() / stepSamples.toDouble()))
                    val env = attackEnv * decayEnv

                    var sampleSum = 0.0
                    for (freq in step.frequenciesHz) {
                        // Fundamental + gentle 2nd harmonic for warm desktop marimba/bell timbre
                        val fundamental = sin(2.0 * PI * freq * t)
                        val overtone = 0.22 * sin(2.0 * PI * (freq * 2.0) * t) * exp(-8.0 * (i.toDouble() / stepSamples.toDouble()))
                        sampleSum += (fundamental + overtone) * perFreqGain * env
                    }
                    mixBuffer[outIdx] += sampleSum
                }
            }

            val pcm = ShortArray(totalSamples)
            for (i in 0 until totalSamples) {
                val clamped = mixBuffer[i].coerceIn(-0.95, 0.95)
                pcm[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
            }

            val byteSize = pcm.size * 2
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(byteSize.coerceAtLeast(AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(pcm, 0, pcm.size)
            audioTrack.play()
            Thread.sleep(totalDurationMs.toLong() + 80L)
            audioTrack.stop()
            audioTrack.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to play synthesized notification chime: ${e.message}")
        }
    }
}
