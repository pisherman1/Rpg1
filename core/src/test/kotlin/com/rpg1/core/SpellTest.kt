package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SpellTest {
    /** Plays until it's a hero of [cls]'s turn in combat with a visible foe in range; returns the game. */
    private fun combatTurnFor(cls: CharClass, seed: Int, setup: (Hero) -> Unit): Pair<Game, Hero> {
        for (s in seed until seed + 40) {
            val dice = Dice(Random(s))
            val b = PartyBuilder(dice)
            b.drafts[0].charClass = cls
            val game = Game(b.build(), dice)
            val hero = game.party[0]
            hero.gainXp(Hero.XP_TABLE[5], dice) // level 6: 3rd-level slots
            setup(hero)
            var ticks = 0
            while (ticks++ < 20_000 && game.phase != Phase.GAME_OVER) {
                if (game.phase == Phase.PLAYER_TURN && game.current === hero && !hero.isUnconscious &&
                    game.monsters.any { game.dungeon.isVisible(it.x, it.y) && hero.distanceTo(it) <= 8 && game.dungeon.hasLineOfSight(hero.x, hero.y, it.x, it.y) }
                ) return game to hero
                if (game.isBusy) game.tick() else if (game.phase == Phase.PLAYER_TURN && game.current === hero) {
                    game.perform(game.availableActions().first { it.id == ActionId.END_TURN })
                } else Bot.play(game, 1, maxDepth = 3)
            }
        }
        fail("never reached a combat turn for $cls")
    }

    private fun Game.visibleFoe(h: Hero) = monsters.filter {
        dungeon.isVisible(it.x, it.y) && h.distanceTo(it) <= 8 && dungeon.hasLineOfSight(h.x, h.y, it.x, it.y)
    }.minBy { h.distanceTo(it) }

    @Test
    fun fireballDamagesTheTargetAndSpendsAThirdLevelSlot() {
        val (game, wiz) = combatTurnFor(CharClass.WIZARD, 1) { it.knownSpells += Spell.FIREBALL }
        val foe = game.visibleFoe(wiz)
        val hpBefore = foe.hp
        val slots = wiz.spellSlots[2]
        game.castSpell(wiz, Spell.FIREBALL, foe, null)
        assertEquals(slots - 1, wiz.spellSlots[2])
        assertTrue(foe.hp < hpBefore || foe.isDead, "fireball should always deal damage (half on a save)")
        assertTrue(game.log.any { it.text.contains("DEX save") })
    }

    @Test
    fun sleepPutsWeakFoesToSleepAndTheyLoseTurns() {
        val (game, wiz) = combatTurnFor(CharClass.WIZARD, 50) { it.knownSpells += Spell.SLEEP }
        val foe = game.visibleFoe(wiz)
        game.castSpell(wiz, Spell.SLEEP, foe, null)
        val sleepers = game.monsters.filter { it.asleep }
        if (sleepers.isNotEmpty()) {
            val m = sleepers.first()
            val hp = m.hp
            // Sleepers make no attacks; damage wakes them.
            game.perform(game.availableActions().first { it.id == ActionId.END_TURN })
            var guard = 0
            while (game.current !== m && guard++ < 50 && game.inCombat) game.tick()
            if (game.current === m) {
                game.tick()
                assertTrue(game.log.last().text.contains("asleep"))
            }
            assertEquals(hp, m.hp)
        } else {
            assertTrue(game.log.any { it.text.contains("No one succumbs") })
        }
    }

    @Test
    fun healingWordIsABonusActionAndBlessBlessesAllies() {
        val (game, cleric) = combatTurnFor(CharClass.CLERIC, 7) {
            it.knownSpells += Spell.HEALING_WORD
            it.knownSpells += Spell.BLESS
        }
        val ally = game.livingHeroes.first { it !== cleric && it.distanceTo(cleric) <= 6 }
        ally.hp = maxOf(1, ally.hp - 3)
        val before = ally.hp
        game.castSpell(cleric, Spell.HEALING_WORD, null, ally)
        assertTrue(ally.hp > before)
        assertEquals(false, game.bonusActionAvailable)
        assertEquals(true, game.actionAvailable, "healing word should not use the action")
        game.castSpell(cleric, Spell.BLESS, null, null)
        assertTrue(cleric.blessed)
        assertEquals(false, game.actionAvailable)
    }

    @Test
    fun outOfCombatOnlySpellsAreRefusedInCombat() {
        val (game, cleric) = combatTurnFor(CharClass.CLERIC, 21) { it.knownSpells += Spell.PRAYER_OF_HEALING }
        val slots = cleric.spellSlots.sum()
        game.castSpell(cleric, Spell.PRAYER_OF_HEALING, null, null)
        assertEquals(slots, cleric.spellSlots.sum())
        assertTrue(game.availableActions().none { it.spell == Spell.PRAYER_OF_HEALING })
    }
}
