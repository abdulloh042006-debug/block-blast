package com.ultimategames.blockblast.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Lightweight, zero-external-asset procedural sound synthesizer for Block Blast Ultimate.
 *
 * Synthesizes clean, soft-clipped (`tanh` limiter at -3 dBFS headroom) 16-bit mono PCM buffers
 * at initialization and plays them through pre-allocated static [AudioTrack] instances:
 * - [Cue.PLACE_BLOCK]: Tactile warm marimba/wood pop (340 Hz -> 520 Hz pitch envelope).
 * - [Cue.LINE_CLEAR]: Bright harmonic triad chime (C5 - E5 - G5).
 * - [Cue.COMBO_CLEAR]: Ascending major 9th arpeggio (C5 - E5 - G5 - B5 - D6).
 * - [Cue.NEW_RECORD]: Triumphant fanfare chord progression.
 * - [Cue.INVALID_MOVE]: Soft muted low double-thud (150 Hz -> 110 Hz).
 * - [Cue.GAME_OVER]: Gentle descending minor cadence (A4 -> F4 -> D4 -> A3).
 */
class SoundEffectsManager(initialEnabled: Boolean = true) {

    enum class Cue {
        PLACE_BLOCK,
        LINE_CLEAR,
        COMBO_CLEAR,
        NEW_RECORD,
        INVALID_MOVE,
        GAME_OVER
    }

    @Volatile
    var isSoundEnabled: Boolean = initialEnabled

    private val tracks = mutableMapOf<Cue, AudioTrack>()
    private var isReleased = false

    init {
        initializeStaticTracks()
    }

    private fun initializeStaticTracks() {
        val cuesAndBuffers = mapOf(
            Cue.PLACE_BLOCK to generateToneSequence(
                notesHz = floatArrayOf(360f, 520f),
                noteDurationSec = 0.045f,
                masterGain = 0.45f
            ),
            Cue.LINE_CLEAR to generateToneSequence(
                notesHz = floatArrayOf(523.25f, 659.25f, 783.99f),
                noteDurationSec = 0.075f,
                masterGain = 0.50f
            ),
            Cue.COMBO_CLEAR to generateToneSequence(
                notesHz = floatArrayOf(523.25f, 659.25f, 783.99f, 987.77f, 1174.66f),
                noteDurationSec = 0.068f,
                masterGain = 0.55f
            ),
            Cue.NEW_RECORD to generateToneSequence(
                notesHz = floatArrayOf(587.33f, 739.99f, 880.0f, 1174.66f),
                noteDurationSec = 0.090f,
                masterGain = 0.55f
            ),
            Cue.INVALID_MOVE to generateToneSequence(
                notesHz = floatArrayOf(155f, 115f),
                noteDurationSec = 0.055f,
                masterGain = 0.35f
            ),
            Cue.GAME_OVER to generateToneSequence(
                notesHz = floatArrayOf(440.0f, 392.0f, 349.23f, 293.66f),
                noteDurationSec = 0.120f,
                masterGain = 0.48f
            )
        )

        for ((cue, pcmSamples) in cuesAndBuffers) {
            try {
                val byteSize = pcmSamples.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
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
                    .setBufferSizeInBytes(byteSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcmSamples, 0, pcmSamples.size)
                tracks[cue] = track
            } catch (_: Exception) {
                // Gracefully ignore audio initialization failures on headless/no-audio devices
            }
        }
    }

    @Synchronized
    fun play(cue: Cue) {
        if (!isSoundEnabled || isReleased) return
        val track = tracks[cue] ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.reloadStaticData()
            track.play()
        } catch (_: Exception) {
            // Ensure audio errors never disrupt gameplay
        }
    }

    @Synchronized
    fun release() {
        if (isReleased) return
        isReleased = true
        for (track in tracks.values) {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
        tracks.clear()
    }

    companion object {
        const val SAMPLE_RATE = 22050

        /**
         * Synthesizes a clean, zero-clipping 16-bit PCM waveform with attack/decay envelopes
         * and a hyperbolic tangent (`tanh`) soft-limiter. Pure function testable on JVM.
         */
        fun generateToneSequence(
            notesHz: FloatArray,
            noteDurationSec: Float,
            masterGain: Float
        ): ShortArray {
            val samplesPerNote = (SAMPLE_RATE * noteDurationSec).toInt().coerceAtLeast(1)
            val totalSamples = samplesPerNote * notesHz.size
            val output = ShortArray(totalSamples)
            val safeGain = masterGain.coerceIn(0.05f, 0.70f)

            for (noteIdx in notesHz.indices) {
                val freq = notesHz[noteIdx]
                val baseOffset = noteIdx * samplesPerNote
                for (i in 0 until samplesPerNote) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val attack = (i.toFloat() / (SAMPLE_RATE * 0.006f)).coerceAtMost(1.0f)
                    val decay = exp(-4.5 * (i.toDouble() / samplesPerNote)).toFloat()
                    val envelope = attack * decay

                    // Fundamental + soft octave overtone
                    val rawWave = (0.82 * sin(2.0 * PI * freq * t) +
                        0.18 * sin(4.0 * PI * freq * t)).toFloat()
                    val limited = tanh((rawWave * envelope * safeGain).toDouble()).toFloat()
                    val pcmVal = (limited * 28000f).toInt().coerceIn(-32767, 32767)
                    output[baseOffset + i] = pcmVal.toShort()
                }
            }
            return output
        }
    }
}
