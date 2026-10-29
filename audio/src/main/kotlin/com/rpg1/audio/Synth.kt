package com.rpg1.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Chiptune waveforms. */
enum class Wave { SQUARE, PULSE25, PULSE12, TRIANGLE, SAW, SINE, NOISE }

/** Attack/decay/sustain/release envelope; times in seconds, sustain as a level 0..1. */
data class Env(val attack: Double = 0.005, val decay: Double = 0.1, val sustain: Double = 0.7, val release: Double = 0.08) {
    /** Level at time [t] for a note held for [hold] seconds. */
    fun level(t: Double, hold: Double): Double {
        val held = when {
            t < attack -> t / attack
            t < attack + decay -> 1.0 - (1.0 - sustain) * (t - attack) / decay
            else -> sustain
        }
        if (t <= hold) return held
        val atRelease = level(hold, hold)
        val r = (t - hold) / release
        return if (r >= 1.0) 0.0 else atRelease * (1.0 - r)
    }

    fun length(hold: Double) = hold + release
}

/** Renders tones and noise into float buffers at [rate] samples per second. */
class Synth(val rate: Int = RATE, seed: Int = 1) {
    private val rng = Random(seed)

    fun buffer(seconds: Double) = FloatArray((seconds * rate).toInt() + 1)

    private fun osc(wave: Wave, phase: Double): Double {
        val p = phase - floor(phase)
        return when (wave) {
            Wave.SQUARE -> if (p < 0.5) 1.0 else -1.0
            Wave.PULSE25 -> if (p < 0.25) 1.0 else -1.0
            Wave.PULSE12 -> if (p < 0.125) 1.0 else -1.0
            Wave.TRIANGLE -> 4.0 * abs(p - 0.5) - 1.0
            Wave.SAW -> 2.0 * p - 1.0
            Wave.SINE -> sin(2 * PI * p)
            Wave.NOISE -> 0.0
        }
    }

    /**
     * Adds a note to [buf] starting at [startSec]. The pitch slides exponentially from [f0] to [f1]
     * over the note; [vibrato] adds a pitch wobble of that many semitones at [vibratoHz].
     * Noise "pitch" sets how often a new random value is drawn (higher = hissier).
     */
    fun note(
        buf: FloatArray, startSec: Double, hold: Double, f0: Double, wave: Wave, volume: Double,
        env: Env = Env(), f1: Double = f0, vibrato: Double = 0.0, vibratoHz: Double = 5.5,
    ) {
        val start = (startSec * rate).toInt()
        val total = (env.length(hold) * rate).toInt()
        var phase = 0.0
        var noise = 0.0
        var noisePhase = 1.0
        val slideLen = maxOf(hold, 1e-6)
        for (i in 0 until total) {
            val idx = start + i
            if (idx >= buf.size) break
            if (idx < 0) continue
            val t = i.toDouble() / rate
            val k = minOf(1.0, t / slideLen)
            var f = if (f1 == f0) f0 else f0 * (f1 / f0).pow(k)
            if (vibrato != 0.0) f *= 2.0.pow(vibrato * sin(2 * PI * vibratoHz * t) / 12.0)
            val v = if (wave == Wave.NOISE) {
                noisePhase += f / rate
                if (noisePhase >= 1.0) {
                    noisePhase -= floor(noisePhase)
                    noise = rng.nextDouble() * 2 - 1
                }
                noise
            } else {
                phase += f / rate
                osc(wave, phase)
            }
            buf[idx] += (v * volume * env.level(t, hold)).toFloat()
        }
    }

    // --- Drums ---------------------------------------------------------------

    fun kick(buf: FloatArray, at: Double, volume: Double = 0.9) =
        note(buf, at, 0.09, 160.0, Wave.SINE, volume, Env(0.001, 0.12, 0.0, 0.03), f1 = 42.0)

    fun snare(buf: FloatArray, at: Double, volume: Double = 0.5) {
        note(buf, at, 0.08, 6000.0, Wave.NOISE, volume, Env(0.001, 0.14, 0.0, 0.03))
        note(buf, at, 0.04, 220.0, Wave.TRIANGLE, volume * 0.6, Env(0.001, 0.06, 0.0, 0.02), f1 = 150.0)
    }

    fun hat(buf: FloatArray, at: Double, volume: Double = 0.18) =
        note(buf, at, 0.02, 12000.0, Wave.NOISE, volume, Env(0.001, 0.035, 0.0, 0.01))

    companion object {
        const val RATE = 22050

        /** Frequency of a MIDI note number (A4 = 69 = 440 Hz). */
        fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

        private val NOTE_INDEX = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

        /** "C4", "F#5", "Bb3" -> MIDI number. */
        fun midi(name: String): Int {
            var i = 1
            var semis = NOTE_INDEX[name[0].uppercaseChar()] ?: throw IllegalArgumentException("Bad note $name")
            while (i < name.length && (name[i] == '#' || name[i] == 'b')) {
                semis += if (name[i] == '#') 1 else -1
                i++
            }
            val octave = name.substring(i).toInt()
            return (octave + 1) * 12 + semis
        }
    }
}

/** Buffer effects and conversion. */
object Dsp {
    /** Feedback echo (processes in place; any tail past the end is lost, so render with headroom). */
    fun echo(buf: FloatArray, rate: Int, delaySec: Double, feedback: Double, mix: Double) {
        val d = (delaySec * rate).toInt().coerceAtLeast(1)
        val wet = FloatArray(buf.size)
        for (i in buf.indices) {
            val prev = if (i >= d) wet[i - d] else 0f
            wet[i] = buf[i] * mix.toFloat() + prev * feedback.toFloat()
        }
        for (i in d until buf.size) buf[i] += wet[i - d]
    }

    /** Folds everything after [length] back onto the start, so a loop's tails ring into its beginning. */
    fun foldTail(buf: FloatArray, length: Int): FloatArray {
        val out = buf.copyOf(length)
        for (i in length until buf.size) out[(i - length) % length] += buf[i]
        return out
    }

    /** One-pole low-pass filter; [cutoff] in Hz. */
    fun lowpass(buf: FloatArray, rate: Int, cutoff: Double) {
        val a = 1.0 - exp(-2.0 * PI * cutoff / rate)
        var y = 0.0
        for (i in buf.indices) {
            y += a * (buf[i] - y)
            buf[i] = y.toFloat()
        }
    }

    /** Scales to [peak] and softly limits so nothing clips. */
    fun normalize(buf: FloatArray, peak: Double = 0.85) {
        val max = buf.maxOfOrNull { abs(it) } ?: 0f
        if (max <= 0f) return
        val g = peak / max
        for (i in buf.indices) buf[i] = (buf[i] * g).toFloat()
    }

    fun toPcm16(buf: FloatArray): ShortArray =
        ShortArray(buf.size) { i -> (tanh(buf[i].toDouble() * 1.1) / tanh(1.1) * 32767).toInt().coerceIn(-32768, 32767).toShort() }

    /** 16-bit mono PCM WAV file bytes. */
    fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val out = java.io.ByteArrayOutputStream(44 + dataLen)
        fun int(v: Int) { out.write(v and 255); out.write((v shr 8) and 255); out.write((v shr 16) and 255); out.write((v shr 24) and 255) }
        fun short(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        out.write("RIFF".toByteArray()); int(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(dataLen)
        for (s in pcm) short(s.toInt())
        return out.toByteArray()
    }
}
