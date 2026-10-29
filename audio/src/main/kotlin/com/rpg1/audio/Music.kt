package com.rpg1.audio

import com.rpg1.core.audio.TrackId

/**
 * A tiny chiptune sequencer. Songs are a chord per bar (driving bass and arpeggio channels),
 * a hand-written lead melody ("A4:4" = note A4 for four 16th-note steps, "r:2" = rest) and a
 * drum pattern per bar (K = kick, S = snare, H = hat).
 */
object Music {
    private class Song(
        val bpm: Int,
        val chords: List<String>,
        val lead: String,
        val leadWave: Wave = Wave.PULSE25,
        val leadVolume: Double = 0.22,
        val leadEcho: Boolean = false,
        val bass: (root: Int, s: Synth, buf: FloatArray, at: Double, step: Double) -> Unit,
        val arp: List<Int>? = null,
        val arpStep: Int = 1,
        val arpWave: Wave = Wave.PULSE12,
        val arpVolume: Double = 0.1,
        val drums: String? = null,
        val drumVolume: Double = 1.0,
        val pad: Boolean = false,
    )

    private fun chordTones(chord: String): Pair<Int, List<Int>> {
        var i = 1
        if (i < chord.length && (chord[i] == '#' || chord[i] == 'b')) i++
        val root = chord.substring(0, i)
        val quality = chord.substring(i)
        val intervals = when (quality) {
            "m" -> listOf(0, 3, 7)
            "7" -> listOf(0, 4, 7, 10)
            "m7" -> listOf(0, 3, 7, 10)
            "dim" -> listOf(0, 3, 6)
            else -> listOf(0, 4, 7)
        }
        return Synth.midi(root + "2") to intervals
    }

    private fun melody(text: String): List<Triple<Int?, Int, Int>> {
        val notes = mutableListOf<Triple<Int?, Int, Int>>()
        var step = 0
        for (token in text.split(' ', '|', '\n').filter { it.isNotBlank() }) {
            val (name, len) = token.split(':').let { it[0] to it[1].toInt() }
            notes += Triple(if (name == "r") null else Synth.midi(name), step, len)
            step += len
        }
        return notes
    }

    private fun root2fifth(pattern: List<Pair<Int, Int>>) = { root: Int, s: Synth, buf: FloatArray, at: Double, step: Double ->
        for ((offset, interval) in pattern) {
            s.note(buf, at + offset * step, step * 1.6, Synth.freq(root + interval), Wave.TRIANGLE, 0.5, Env(0.004, 0.05, 0.8, 0.03))
        }
    }

    private val SONGS = mapOf(
        TrackId.TITLE to Song(
            bpm = 96,
            chords = "Am F C G Am F G E F G Em Am Dm G C E".split(' '),
            lead = """
                A4:4 C5:2 E5:2 A5:6 G5:2 | F5:4 E5:2 D5:2 C5:8 | E5:4 G5:2 E5:2 C5:4 D5:4 | B4:8 D5:4 G5:4
                A4:4 C5:2 E5:2 A5:4 B5:2 C6:2 | A5:4 F5:4 C5:4 F5:4 | G5:6 F5:2 E5:4 D5:4 | E5:8 G#5:4 B5:4
                A5:6 G5:2 F5:4 C5:4 | B4:4 D5:4 G5:4 F5:4 | E5:6 D5:2 B4:4 G4:4 | A4:12 r:4
                D5:4 F5:4 A5:4 D6:4 | B5:4 G5:4 D5:4 B4:4 | C5:4 E5:4 G5:4 C6:4 | B5:8 G#5:4 E5:4
            """,
            bass = root2fifth(listOf(0 to 0, 6 to 0, 8 to 7, 14 to 12)),
            arp = listOf(0, 1, 2, 3), arpStep = 1, arpVolume = 0.07,
            drums = "K...H...S...H.H.", drumVolume = 0.55,
        ),
        TrackId.EXPLORE to Song(
            bpm = 72,
            chords = "Am Am Em Em F F Dm E Am Am Em Em F G Am Am".split(' '),
            lead = """
                r:8 E5:4 D5:4 | C5:12 B4:4 | B4:8 G4:4 A4:4 | B4:16 | A4:8 C5:8 | F5:12 E5:4 | D5:8 F5:4 A5:4 | G#5:16
                r:8 E5:4 A5:4 | G5:12 E5:4 | G5:8 F5:4 E5:4 | B4:16 | C5:8 A4:8 | D5:8 B4:8 | C5:8 B4:4 G#4:4 | A4:16
            """,
            leadWave = Wave.TRIANGLE, leadVolume = 0.3, leadEcho = true,
            bass = { root, s, buf, at, step ->
                s.note(buf, at, step * 15, Synth.freq(root), Wave.TRIANGLE, 0.45, Env(0.05, 0.3, 0.7, 0.3))
            },
            arp = listOf(0, 1, 2, 3, 2, 1, 0, 1), arpStep = 2, arpWave = Wave.PULSE12, arpVolume = 0.06,
            drums = "K...............", drumVolume = 0.35, pad = true,
        ),
        TrackId.COMBAT to Song(
            bpm = 144,
            chords = "Em Em C D Em Em C B7 Am C Em D C D B7 B7".split(' '),
            lead = """
                E5:2 E5:2 G5:2 E5:2 A5:2 G5:2 E5:2 D5:2 | E5:2 E5:2 G5:2 E5:2 B5:4 A5:4 | G5:4 E5:4 C5:4 E5:4 | F#5:4 A5:4 D6:4 C6:2 B5:2
                E5:2 E5:2 G5:2 E5:2 A5:2 G5:2 E5:2 D5:2 | E5:2 E5:2 G5:2 E5:2 B5:4 A5:4 | G5:4 E5:4 C6:4 G5:4 | F#5:4 D#5:4 B4:4 F#5:4
                A5:4 C6:4 E6:4 C6:4 | G5:4 C6:4 E6:4 G6:4 | B5:6 G5:2 E5:4 B4:4 | A5:4 F#5:4 D5:4 F#5:4
                G5:4 E5:4 C5:4 E5:4 | F#5:4 A5:4 D6:4 A5:4 | B5:4 A5:2 G5:2 F#5:4 D#5:4 | B4:8 F#5:4 D#5:4
            """,
            leadWave = Wave.SQUARE, leadVolume = 0.16,
            bass = { root, s, buf, at, step ->
                val pattern = listOf(0, 0, 12, 0, 0, 0, 12, 7)
                for ((i, interval) in pattern.withIndex()) {
                    s.note(buf, at + i * 2 * step, step * 1.5, Synth.freq(root + interval), Wave.PULSE25, 0.22, Env(0.002, 0.06, 0.5, 0.02))
                }
            },
            arp = listOf(0, 2, 3, 2), arpStep = 1, arpVolume = 0.06,
            drums = "K.H.S.HKK.H.S.HS", drumVolume = 0.8,
        ),
        TrackId.DEFEAT to Song(
            bpm = 60,
            chords = "Am Dm E Am F Dm E Am".split(' '),
            lead = "E5:8 D5:4 C5:4 | D5:8 F5:4 A4:4 | G#4:8 B4:4 E5:4 | A4:16 | C5:8 B4:4 A4:4 | A4:8 F4:8 | E4:8 G#4:8 | A4:16",
            leadWave = Wave.TRIANGLE, leadVolume = 0.32, leadEcho = true,
            bass = { root, s, buf, at, step ->
                s.note(buf, at, step * 15, Synth.freq(root), Wave.TRIANGLE, 0.4, Env(0.1, 0.5, 0.6, 0.5))
            },
            pad = true,
        ),
    )

    /** Renders a whole track as 16-bit PCM; looping tracks wrap their tails for seamless repeats. */
    fun render(track: TrackId): ShortArray {
        val song = SONGS.getValue(track)
        val s = Synth(seed = track.ordinal + 101)
        val step = 60.0 / song.bpm / 4
        val bar = step * 16
        val length = (song.chords.size * bar * s.rate).toInt()
        val tail = 4.0
        val mix = s.buffer(song.chords.size * bar + tail)
        val leadBuf = s.buffer(song.chords.size * bar + tail)

        song.chords.forEachIndexed { b, chord ->
            val at = b * bar
            val (root, intervals) = chordTones(chord)
            song.bass(root, s, mix, at, step)
            song.arp?.let { pattern ->
                val base = root + 24 - if (root + 24 >= Synth.midi("G4")) 12 else 0
                val tones = intervals.take(3) + (intervals[0] + 12)
                var k = 0
                var st = 0
                while (st < 16) {
                    val tone = tones[pattern[k % pattern.size].coerceAtMost(tones.lastIndex)]
                    s.note(mix, at + st * step, step * song.arpStep * 0.6, Synth.freq(base + tone), song.arpWave, song.arpVolume,
                        Env(0.002, 0.06, 0.3, 0.04))
                    k++
                    st += song.arpStep
                }
            }
            if (song.pad) {
                val base = root + 24 - if (root + 24 >= Synth.midi("G4")) 12 else 0
                for (iv in intervals.take(3)) {
                    s.note(mix, at, bar * 0.95, Synth.freq(base + iv), Wave.SQUARE, 0.025, Env(0.4, 0.5, 0.8, 0.6))
                }
            }
            song.drums?.let { pattern ->
                pattern.forEachIndexed { i, c ->
                    val t = at + i * step
                    when (c) {
                        'K' -> s.kick(mix, t, 0.8 * song.drumVolume)
                        'S' -> s.snare(mix, t, 0.45 * song.drumVolume)
                        'H' -> s.hat(mix, t, 0.16 * song.drumVolume)
                    }
                }
            }
        }

        for ((midi, start, len) in melody(song.lead)) {
            if (midi == null) continue
            s.note(leadBuf, start * step, len * step * 0.9, Synth.freq(midi), song.leadWave, song.leadVolume,
                Env(0.01, 0.1, 0.65, 0.08), vibrato = if (len >= 4) 0.12 else 0.0)
        }
        if (song.leadEcho) Dsp.echo(leadBuf, s.rate, step * 3, 0.4, 0.5)
        for (i in mix.indices) mix[i] += leadBuf[i]
        Dsp.lowpass(mix, s.rate, 7000.0)

        val out = if (track.loop) Dsp.foldTail(mix, length) else mix
        Dsp.normalize(out, 0.8)
        return Dsp.toPcm16(out)
    }

    /** Length of the melody in steps, for validation. */
    internal fun leadSteps(track: TrackId) = melody(SONGS.getValue(track).lead).sumOf { it.third }
    internal fun bars(track: TrackId) = SONGS.getValue(track).chords.size
}
