package com.rpg1.app

import com.rpg1.core.Dice
import com.rpg1.core.Game
import com.rpg1.core.PartyBuilder

/** Holds the current party builder and game across activity recreation. */
object GameSession {
    val dice = Dice()
    var builder = PartyBuilder(dice)
    var game: Game? = null

    fun startGame() {
        game = Game(builder.build(), dice)
    }

    fun newParty() {
        game = null
        builder = PartyBuilder(dice)
    }
}
