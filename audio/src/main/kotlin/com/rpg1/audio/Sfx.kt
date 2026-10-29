package com.rpg1.audio

import com.rpg1.core.audio.SoundId

/** Designs for every sound effect, rendered with [Synth]. */
object Sfx {
    fun render(id: SoundId): ShortArray {
        val s = Synth(seed = id.ordinal + 7)
        val buf = s.buffer(LENGTHS[id] ?: 0.6)
        fun n(at: Double, hold: Double, f0: Double, wave: Wave, vol: Double, env: Env = Env(), f1: Double = f0, vib: Double = 0.0) =
            s.note(buf, at, hold, f0, wave, vol, env, f1, vib)
        fun m(name: String) = Synth.freq(Synth.midi(name))
        val pluck = Env(0.002, 0.08, 0.0, 0.02)
        val chime = Env(0.002, 0.25, 0.25, 0.25)

        when (id) {
            SoundId.CLICK -> n(0.0, 0.015, 1400.0, Wave.SQUARE, 0.25, Env(0.001, 0.02, 0.0, 0.01), f1 = 900.0)
            SoundId.STEP -> {
                n(0.0, 0.02, 900.0, Wave.NOISE, 0.35, Env(0.001, 0.04, 0.0, 0.01))
                n(0.0, 0.02, 120.0, Wave.TRIANGLE, 0.3, Env(0.001, 0.03, 0.0, 0.01), f1 = 80.0)
            }
            SoundId.HIT -> {
                n(0.0, 0.05, 2500.0, Wave.NOISE, 0.6, Env(0.001, 0.09, 0.0, 0.03))
                n(0.0, 0.06, 260.0, Wave.SQUARE, 0.4, Env(0.001, 0.08, 0.0, 0.02), f1 = 90.0)
            }
            SoundId.CRIT -> {
                n(0.0, 0.08, 3000.0, Wave.NOISE, 0.7, Env(0.001, 0.14, 0.0, 0.04))
                n(0.0, 0.1, 400.0, Wave.SQUARE, 0.45, Env(0.001, 0.12, 0.0, 0.03), f1 = 70.0)
                n(0.05, 0.12, m("E6"), Wave.PULSE25, 0.25, chime)
                n(0.05, 0.12, m("B6"), Wave.PULSE25, 0.18, chime)
            }
            SoundId.MISS -> n(0.0, 0.16, 300.0, Wave.NOISE, 0.35, Env(0.04, 0.12, 0.0, 0.05), f1 = 3500.0)
            SoundId.BOW -> {
                n(0.0, 0.03, 600.0, Wave.TRIANGLE, 0.5, pluck, f1 = 300.0)
                n(0.02, 0.15, 1200.0, Wave.NOISE, 0.18, Env(0.01, 0.12, 0.0, 0.03), f1 = 5000.0)
            }
            SoundId.FIRE_CAST -> {
                n(0.0, 0.3, 400.0, Wave.NOISE, 0.4, Env(0.03, 0.25, 0.3, 0.1), f1 = 2500.0)
                n(0.0, 0.25, 180.0, Wave.SAW, 0.18, Env(0.02, 0.2, 0.2, 0.1), f1 = 420.0)
            }
            SoundId.EXPLOSION -> {
                n(0.0, 0.25, 1800.0, Wave.NOISE, 0.8, Env(0.002, 0.45, 0.0, 0.1), f1 = 300.0)
                n(0.0, 0.2, 110.0, Wave.SINE, 0.7, Env(0.002, 0.35, 0.0, 0.1), f1 = 35.0)
            }
            SoundId.MAGIC -> {
                n(0.0, 0.2, 500.0, Wave.PULSE25, 0.25, Env(0.01, 0.2, 0.2, 0.1), f1 = 1500.0, vib = 0.4)
                n(0.03, 0.2, 750.0, Wave.SINE, 0.25, Env(0.01, 0.2, 0.2, 0.1), f1 = 2200.0)
            }
            SoundId.ZAP -> {
                n(0.0, 0.18, 2000.0, Wave.SAW, 0.3, Env(0.001, 0.15, 0.2, 0.05), f1 = 300.0, vib = 3.0)
                n(0.0, 0.15, 8000.0, Wave.NOISE, 0.3, Env(0.001, 0.15, 0.0, 0.05))
            }
            SoundId.THUNDER -> {
                n(0.0, 0.6, 700.0, Wave.NOISE, 0.8, Env(0.002, 0.9, 0.0, 0.2), f1 = 120.0)
                n(0.0, 0.5, 70.0, Wave.SINE, 0.6, Env(0.01, 0.8, 0.0, 0.2), f1 = 30.0)
                n(0.25, 0.4, 400.0, Wave.NOISE, 0.4, Env(0.05, 0.6, 0.0, 0.2), f1 = 90.0)
            }
            SoundId.HOLY -> for ((i, note) in listOf("A5", "C#6", "E6", "A6").withIndex()) {
                n(i * 0.05, 0.25, m(note), Wave.SINE, 0.28, Env(0.01, 0.3, 0.3, 0.35))
                n(i * 0.05, 0.25, m(note) * 2.003, Wave.SINE, 0.08, Env(0.01, 0.3, 0.3, 0.35))
            }
            SoundId.NECRO -> {
                n(0.0, 0.4, 220.0, Wave.SAW, 0.22, Env(0.05, 0.3, 0.4, 0.2), f1 = 110.0, vib = 0.8)
                n(0.0, 0.4, 233.0, Wave.SAW, 0.18, Env(0.05, 0.3, 0.4, 0.2), f1 = 104.0, vib = 0.8)
            }
            SoundId.HEAL -> for ((i, note) in listOf("C5", "E5", "G5", "C6", "E6").withIndex()) {
                n(i * 0.06, 0.15, m(note), Wave.TRIANGLE, 0.35, chime)
            }
            SoundId.POTION -> {
                for (i in 0 until 3) n(i * 0.09, 0.05, 300.0 + i * 90, Wave.SINE, 0.4, Env(0.005, 0.06, 0.0, 0.02), f1 = 600.0 + i * 120)
                n(0.3, 0.1, m("A5"), Wave.TRIANGLE, 0.25, chime)
            }
            SoundId.COIN -> {
                n(0.0, 0.05, m("B5"), Wave.SQUARE, 0.25, Env(0.001, 0.05, 0.5, 0.02))
                n(0.06, 0.2, m("E6"), Wave.SQUARE, 0.25, Env(0.001, 0.1, 0.4, 0.15))
            }
            SoundId.CHEST -> {
                n(0.0, 0.15, 180.0, Wave.SAW, 0.25, Env(0.01, 0.15, 0.0, 0.05), f1 = 120.0)
                n(0.0, 0.1, 900.0, Wave.NOISE, 0.2, Env(0.005, 0.1, 0.0, 0.05))
                for ((i, note) in listOf("G5", "C6", "E6").withIndex()) n(0.18 + i * 0.07, 0.12, m(note), Wave.PULSE25, 0.22, chime)
            }
            SoundId.LOOT -> for ((i, note) in listOf("E5", "G#5", "B5", "E6", "G#6", "B6").withIndex()) {
                n(i * 0.05, 0.1, m(note), Wave.PULSE12, 0.25, Env(0.002, 0.12, 0.3, 0.2))
                n(i * 0.05 + 0.02, 0.1, m(note) * 2, Wave.SINE, 0.1, Env(0.002, 0.12, 0.3, 0.2))
            }
            SoundId.LEVEL_UP -> {
                for ((i, note) in listOf("C5", "E5", "G5", "C6", "E6", "G6").withIndex()) n(i * 0.06, 0.08, m(note), Wave.PULSE25, 0.25, chime)
                for (note in listOf("C6", "E6", "G6")) n(0.42, 0.5, m(note), Wave.SQUARE, 0.12, Env(0.01, 0.2, 0.6, 0.4), vib = 0.15)
            }
            SoundId.MONSTER_DIE -> {
                n(0.0, 0.3, 300.0, Wave.SQUARE, 0.3, Env(0.005, 0.3, 0.2, 0.1), f1 = 60.0, vib = 0.6)
                n(0.0, 0.25, 1500.0, Wave.NOISE, 0.25, Env(0.005, 0.3, 0.0, 0.1), f1 = 200.0)
            }
            SoundId.HERO_DOWN -> {
                for ((i, note) in listOf("E5", "C5", "A4").withIndex()) n(i * 0.12, 0.12, m(note), Wave.PULSE25, 0.28, Env(0.005, 0.1, 0.5, 0.1))
                n(0.0, 0.15, 800.0, Wave.NOISE, 0.3, Env(0.001, 0.15, 0.0, 0.05), f1 = 200.0)
            }
            SoundId.HERO_DIE -> {
                for ((i, note) in listOf("E4", "D#4", "D4", "C#4").withIndex()) n(i * 0.25, 0.25, m(note), Wave.SQUARE, 0.2, Env(0.01, 0.1, 0.6, 0.15), vib = 0.25)
                n(1.0, 0.6, m("A2"), Wave.TRIANGLE, 0.5, Env(0.01, 0.4, 0.5, 0.5))
            }
            SoundId.DESCEND -> for (i in 0 until 6) {
                n(i * 0.09, 0.05, 900.0 - i * 110, Wave.NOISE, 0.3, Env(0.001, 0.06, 0.0, 0.02))
                n(i * 0.09, 0.05, 180.0 - i * 18, Wave.TRIANGLE, 0.35, Env(0.001, 0.06, 0.0, 0.02))
            }
            SoundId.COMBAT_START -> {
                n(0.0, 0.1, m("E3"), Wave.SAW, 0.3, Env(0.005, 0.1, 0.7, 0.1))
                n(0.12, 0.35, m("B3"), Wave.SAW, 0.3, Env(0.005, 0.1, 0.7, 0.2), vib = 0.2)
                n(0.12, 0.35, m("E4"), Wave.SQUARE, 0.15, Env(0.005, 0.1, 0.7, 0.2), vib = 0.2)
                s.snare(buf, 0.0, 0.4)
                s.kick(buf, 0.12, 0.6)
            }
            SoundId.VICTORY -> {
                val seq = listOf("C5" to 0.0, "E5" to 0.1, "G5" to 0.2, "C6" to 0.3)
                for ((note, at) in seq) n(at, 0.08, m(note), Wave.PULSE25, 0.28, Env(0.005, 0.05, 0.7, 0.05))
                for (note in listOf("G5", "C6", "E6")) n(0.42, 0.55, m(note), Wave.SQUARE, 0.14, Env(0.005, 0.2, 0.7, 0.3), vib = 0.12)
                n(0.42, 0.55, m("C3"), Wave.TRIANGLE, 0.45, Env(0.005, 0.2, 0.7, 0.3))
            }
            SoundId.SLEEP -> for ((i, note) in listOf("G5", "E5", "C5").withIndex()) {
                n(i * 0.18, 0.2, m(note), Wave.SINE, 0.3, Env(0.05, 0.2, 0.4, 0.3), vib = 0.3)
            }
            SoundId.CHALLENGE -> {
                n(0.0, 0.15, m("G4"), Wave.SAW, 0.25, Env(0.01, 0.05, 0.8, 0.05))
                n(0.17, 0.35, m("C5"), Wave.SAW, 0.25, Env(0.01, 0.05, 0.8, 0.2), vib = 0.2)
                n(0.17, 0.35, m("G5"), Wave.SQUARE, 0.12, Env(0.01, 0.05, 0.8, 0.2), vib = 0.2)
            }
            SoundId.MARK -> {
                n(0.0, 0.04, 1800.0, Wave.SINE, 0.3, Env(0.001, 0.06, 0.0, 0.03))
                n(0.07, 0.12, 2400.0, Wave.SINE, 0.25, Env(0.001, 0.1, 0.2, 0.1))
            }
            SoundId.RAISE -> {
                n(0.0, 0.6, 80.0, Wave.SAW, 0.25, Env(0.1, 0.4, 0.5, 0.2), f1 = 160.0, vib = 0.5)
                n(0.0, 0.6, 85.0, Wave.SAW, 0.2, Env(0.1, 0.4, 0.5, 0.2), f1 = 170.0, vib = 0.5)
                for (i in 0 until 5) n(0.2 + i * 0.08, 0.03, 2500.0, Wave.NOISE, 0.2, Env(0.001, 0.04, 0.0, 0.01))
            }
            SoundId.REST -> for ((i, note) in listOf("A4", "C5", "E5", "A5").withIndex()) {
                n(i * 0.2, 0.4, m(note), Wave.TRIANGLE, 0.3, Env(0.02, 0.4, 0.4, 0.4))
            }
        }
        if (id == SoundId.THUNDER || id == SoundId.EXPLOSION) Dsp.lowpass(buf, s.rate, 5000.0)
        Dsp.normalize(buf, VOLUME[id] ?: 0.8)
        return Dsp.toPcm16(trimSilence(buf))
    }

    private fun trimSilence(buf: FloatArray): FloatArray {
        var end = buf.size
        while (end > 1 && kotlin.math.abs(buf[end - 1]) < 1e-4f) end--
        return buf.copyOf(maxOf(end, 1))
    }

    private val LENGTHS = mapOf(
        SoundId.THUNDER to 1.4, SoundId.HERO_DIE to 2.2, SoundId.LEVEL_UP to 1.4, SoundId.VICTORY to 1.4,
        SoundId.REST to 1.8, SoundId.RAISE to 1.2, SoundId.DESCEND to 0.9, SoundId.HOLY to 1.0,
        SoundId.LOOT to 0.9, SoundId.CHEST to 0.9, SoundId.SLEEP to 1.2,
    )

    /** Relative loudness so quick, frequent sounds sit under the big moments. */
    private val VOLUME = mapOf(
        SoundId.STEP to 0.25, SoundId.CLICK to 0.35, SoundId.MISS to 0.5, SoundId.HIT to 0.75,
        SoundId.COIN to 0.55, SoundId.BOW to 0.6,
    )
}
