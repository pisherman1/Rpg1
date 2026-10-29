package com.rpg1.app

import android.content.Context
import com.rpg1.core.Dice
import com.rpg1.core.Game
import com.rpg1.core.PartyBuilder
import com.rpg1.core.Phase

/** Holds the current party builder, game and save store across activity recreation. */
object GameSession {
    val dice = Dice()
    var builder = PartyBuilder(dice)
    var game: Game? = null
    lateinit var store: SaveStore
        private set

    fun init(context: Context) {
        if (!::store.isInitialized) store = SaveStore(context.applicationContext)
    }

    fun startGame() {
        game = Game(builder.build(), dice)
        autosave()
    }

    fun newParty() {
        game = null
        builder = PartyBuilder(dice)
    }

    /** Saves the running game to the autosave slot; a fallen party's autosave is removed instead. */
    fun autosave() {
        val g = game ?: return
        try {
            if (g.phase == Phase.GAME_OVER) store.delete(SaveStore.AUTOSAVE) else store.save(SaveStore.AUTOSAVE, g)
        } catch (e: Exception) {
            // Saving must never crash the game; the next autosave will try again.
        }
    }

    fun load(slot: Int) {
        game = store.load(slot)
    }
}
