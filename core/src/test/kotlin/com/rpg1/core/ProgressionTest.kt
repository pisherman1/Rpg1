package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressionTest {
    private fun hero(cls: CharClass) = Hero.create("T", Race.HUMAN, cls, listOf(15, 14, 13, 12, 10, 8))

    @Test
    fun everyLevelBringsSomethingNew() {
        for (cls in CharClass.entries) {
            val h = hero(cls)
            for (lvl in 2..Hero.MAX_LEVEL) assertTrue(h.levelGains(lvl).isNotEmpty(), "$cls level $lvl gains nothing")
        }
    }

    @Test
    fun castersStartSmallAndGrowEachLevel() {
        assertEquals(listOf(Spell.FIRE_BOLT, Spell.MAGIC_MISSILE), hero(CharClass.WIZARD).knownSpells)
        assertEquals(listOf(Spell.SACRED_FLAME, Spell.CURE_WOUNDS), hero(CharClass.CLERIC).knownSpells)
        val w = hero(CharClass.WIZARD)
        for (lvl in 2..Hero.MAX_LEVEL) {
            w.gainXp(Hero.XP_TABLE[lvl - 1] - w.xp, Dice(Random(1)))
            assertEquals(1, w.pendingSpells, "a spell pick at level $lvl")
            assertTrue(w.learnSpell(w.learnableSpells().first()))
        }
        assertEquals(2 + 9, w.knownSpells.size)
    }

    @Test
    fun highLevelMartialFeatures() {
        val f = hero(CharClass.FIGHTER)
        f.gainXp(Hero.XP_TABLE[8], Dice(Random(1)))
        assertEquals(9, f.level)
        assertEquals(1, f.uses(Resource.INDOMITABLE))
        assertEquals(2, f.attacksPerAction)
        f.gainXp(Hero.XP_TABLE[9] - f.xp, Dice(Random(1)))
        assertEquals(3, f.attacksPerAction)

        val r = hero(CharClass.ROGUE)
        val ac = r.armorClass
        r.gainXp(Hero.XP_TABLE[5], Dice(Random(1)))
        assertEquals(ac + 1, r.armorClass, "Evasive at 6th")
        r.gainXp(Hero.XP_TABLE[9] - r.xp, Dice(Random(1)))
        assertEquals(6, r.pendingAbilityPoints, "ASIs at 4, 8 and 10")
    }

    @Test
    fun xpCurveIsGentlerThanTheBook() {
        assertTrue(Hero.XP_TABLE[1] < 300 && Hero.XP_TABLE[4] < 6500 / 3)
        assertTrue(Hero.XP_TABLE.toList().zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun levelUpsAreAnnounced() {
        val dice = Dice(Random(12))
        val game = Game(PartyBuilder(dice).build(), dice)
        var notices = emptyList<LevelUpNotice>()
        var ticks = 0
        while (notices.isEmpty() && ticks++ < 20_000 && game.phase != Phase.GAME_OVER) {
            Bot.play(game, 1, maxDepth = 4)
            notices = game.drainLevelUps()
        }
        assertTrue(notices.isNotEmpty(), "no level-up within the first fights")
        val n = notices.first()
        assertEquals(2, n.level)
        assertTrue(n.hpGain > 0 && n.gains.isNotEmpty())
    }
}
