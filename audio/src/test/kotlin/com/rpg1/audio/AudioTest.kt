package com.rpg1.audio

import com.rpg1.core.audio.SoundId
import com.rpg1.core.audio.TrackId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {
    private fun rms(pcm: ShortArray) = kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

    @Test
    fun noteNamesMapToMidiAndFrequency() {
        assertEquals(69, Synth.midi("A4"))
        assertEquals(60, Synth.midi("C4"))
        assertEquals(61, Synth.midi("C#4"))
        assertEquals(70, Synth.midi("Bb4"))
        assertEquals(440.0, Synth.freq(69), 1e-9)
    }

    @Test
    fun everySoundEffectIsAudibleShortAndUnclipped() {
        for (id in SoundId.entries) {
            val pcm = Sfx.render(id)
            val seconds = pcm.size.toDouble() / Synth.RATE
            assertTrue(seconds in 0.01..2.5, "$id lasts $seconds s")
            assertTrue(rms(pcm) > 300, "$id is too quiet (rms ${rms(pcm)})")
            assertTrue(pcm.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE }, "$id clips")
        }
    }

    @Test
    fun melodiesFillEveryBar() {
        for (t in TrackId.entries) assertEquals(Music.bars(t) * 16, Music.leadSteps(t), "$t melody length")
    }

    @Test
    fun tracksRenderAtTheRightLengthAndLoopSeamlessly() {
        for (t in TrackId.entries) {
            val pcm = Music.render(t)
            val seconds = pcm.size.toDouble() / Synth.RATE
            assertTrue(seconds in 20.0..70.0, "$t lasts $seconds s")
            assertTrue(rms(pcm) > 1500, "$t is too quiet")
            if (t.loop) {
                // The end flows into the start without a big jump.
                val jump = abs(pcm.last().toInt() - pcm.first().toInt())
                assertTrue(jump < 12000, "$t loop seam jumps by $jump")
            }
        }
    }

    @Test
    fun wavHeaderIsValid() {
        val pcm = Sfx.render(SoundId.COIN)
        val wav = Dsp.wav(pcm, Synth.RATE)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(44 + pcm.size * 2, wav.size)
    }
}
