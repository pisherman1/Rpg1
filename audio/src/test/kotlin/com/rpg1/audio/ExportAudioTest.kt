package com.rpg1.audio

import com.rpg1.core.audio.SoundId
import com.rpg1.core.audio.TrackId
import java.io.File
import kotlin.test.Test

/** Writes every sound and track as WAV when EXPORT_AUDIO_DIR is set (used to build the Godot version's assets). */
class ExportAudioTest {
    @Test
    fun exportWhenRequested() {
        val dir = System.getenv("EXPORT_AUDIO_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for (id in SoundId.entries) File(dir, "sfx_${id.name.lowercase()}.wav").writeBytes(Dsp.wav(Sfx.render(id), Synth.RATE))
        for (t in TrackId.entries) File(dir, "music_${t.name.lowercase()}.wav").writeBytes(Dsp.wav(Music.render(t), Synth.RATE))
    }
}
