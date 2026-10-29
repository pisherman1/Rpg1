package com.rpg1.app

import android.content.Context
import com.rpg1.core.Game
import com.rpg1.core.Json
import java.io.File

/** Save files in the app's private storage: one autosave plus numbered manual slots. */
class SaveStore(context: Context) {
    data class SlotInfo(val summary: String, val savedAt: Long)

    private val dir = File(context.filesDir, "saves").apply { mkdirs() }

    private fun file(slot: Int) = File(dir, if (slot == AUTOSAVE) "autosave.json" else "slot$slot.json")

    fun save(slot: Int, game: Game) {
        val data = game.toSave() + ("savedAt" to System.currentTimeMillis())
        val target = file(slot)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(Json.write(data))
        // Write-then-rename so a crash mid-save never leaves a half-written file.
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /** Returns the loaded game, or throws with a readable message. */
    fun load(slot: Int): Game {
        val f = file(slot)
        if (!f.exists()) throw IllegalArgumentException("That slot is empty")
        return Game.fromJson(f.readText())
    }

    fun info(slot: Int): SlotInfo? {
        val f = file(slot)
        if (!f.exists()) return null
        return try {
            val map = Json.parse(f.readText()) as Map<*, *>
            SlotInfo(map["summary"] as? String ?: "Saved game", (map["savedAt"] as? Number)?.toLong() ?: f.lastModified())
        } catch (e: Exception) {
            SlotInfo("Unreadable save", f.lastModified())
        }
    }

    fun delete(slot: Int) {
        file(slot).delete()
    }

    companion object {
        const val AUTOSAVE = 0
        val MANUAL_SLOTS = 1..3
    }
}
