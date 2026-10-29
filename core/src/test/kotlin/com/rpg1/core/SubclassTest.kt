package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SubclassTest {
    private fun leveled(cls: CharClass, sub: Subclass, level: Int, race: Race = Race.HUMAN): Hero {
        val h = Hero.create("T", race, cls, listOf(15, 14, 13, 12, 10, 8))
        h.gainXp(Hero.XP_TABLE[level - 1], Dice(Random(1)))
        assertTrue(h.chooseSubclass(sub))
        return h
    }

    @Test
    fun subclassIsChosenAtThirdLevelForTheRightClass() {
        val f = Hero.create("F", Race.HUMAN, CharClass.FIGHTER, listOf(15, 14, 13, 12, 10, 8))
        assertFalse(f.needsSubclass)
        f.gainXp(Hero.XP_TABLE[2], Dice(Random(1)))
        assertTrue(f.needsSubclass)
        assertTrue(f.hasPendingChoices)
        assertFalse(f.chooseSubclass(Subclass.NECROMANCER))
        assertTrue(f.chooseSubclass(Subclass.KNIGHT))
        assertFalse(f.chooseSubclass(Subclass.PALADIN), "can't change once chosen")
        assertEquals(2, Subclass.entries.count { it.charClass == CharClass.FIGHTER })
        for (cls in CharClass.entries) assertEquals(2, Subclass.forClass(cls).size)
        Subclass.entries.forEach { assertTrue(it.features.size >= 3, "${it.name} has features") }
    }

    @Test
    fun passiveFeaturesChangeNumbers() {
        val base = Hero.create("K", Race.HUMAN, CharClass.FIGHTER, listOf(15, 14, 13, 12, 10, 8))
        val knight = leveled(CharClass.FIGHTER, Subclass.KNIGHT, 3)
        assertEquals(base.armorClass + 1, knight.armorClass)

        val ranger = leveled(CharClass.ROGUE, Subclass.RANGER, 3)
        assertEquals(Weapons.LONGBOW, ranger.rangedWeapon)
        val plain = leveled(CharClass.ROGUE, Subclass.ASSASSIN, 3)
        assertEquals(plain.attackBonus(Weapons.LONGBOW) + 2, ranger.attackBonus(Weapons.LONGBOW))
    }

    @Test
    fun resourcesRechargeOnTheRightRests() {
        val pal = leveled(CharClass.FIGHTER, Subclass.PALADIN, 3)
        assertEquals(2, pal.uses(Resource.SMITE))
        assertEquals(15, pal.uses(Resource.LAY_ON_HANDS))
        assertTrue(pal.spend(Resource.SMITE))
        assertTrue(pal.spend(Resource.LAY_ON_HANDS, 10))
        pal.shortRest(Dice(Random(2)))
        assertEquals(1, pal.uses(Resource.SMITE), "smite is a long-rest resource")
        pal.longRest()
        assertEquals(2, pal.uses(Resource.SMITE))
        assertEquals(15, pal.uses(Resource.LAY_ON_HANDS))

        val asn = leveled(CharClass.ROGUE, Subclass.ASSASSIN, 3)
        asn.spend(Resource.ENVENOM, 2)
        assertEquals(0, asn.uses(Resource.ENVENOM))
        asn.shortRest(Dice(Random(2)))
        assertEquals(2, asn.uses(Resource.ENVENOM))
    }

    @Test
    fun domainSpellsAreGranted() {
        val priest = leveled(CharClass.CLERIC, Subclass.PRIEST, 3)
        assertTrue(Spell.BLESS in priest.knownSpells)
        assertFalse(Spell.REVIVIFY in priest.knownSpells)
        priest.gainXp(Hero.XP_TABLE[4] - priest.xp, Dice(Random(1)))
        assertTrue(Spell.REVIVIFY in priest.knownSpells)

        val storm = leveled(CharClass.CLERIC, Subclass.TEMPEST, 3)
        assertTrue(Spell.THUNDERWAVE in storm.knownSpells)
        storm.gainXp(Hero.XP_TABLE[4] - storm.xp, Dice(Random(1)))
        assertTrue(Spell.CALL_LIGHTNING in storm.knownSpells)
        assertTrue(storm.learnableSpells().none { it == Spell.THUNDERWAVE || it == Spell.CALL_LIGHTNING })
    }

    @Test
    fun subclassStateSurvivesSaving() {
        val dice = Dice(Random(3))
        val b = PartyBuilder(dice)
        b.drafts[0].charClass = CharClass.FIGHTER
        val game = Game(b.build(), dice)
        val h = game.party[0]
        h.gainXp(Hero.XP_TABLE[2], dice)
        h.chooseSubclass(Subclass.PALADIN)
        h.spend(Resource.SMITE)
        h.smiteReady = true
        val loaded = Game.fromJson(game.toJson()).party[0]
        assertEquals(Subclass.PALADIN, loaded.subclass)
        assertEquals(1, loaded.uses(Resource.SMITE))
        assertTrue(loaded.smiteReady)
    }

    /** Plays until the first hero (of [cls]/[sub] at [level]) gets a combat turn near a visible foe. */
    private fun combatTurn(cls: CharClass, sub: Subclass, level: Int, seed: Int = 1, ready: (Game, Hero) -> Boolean = { _, _ -> true }): Pair<Game, Hero> {
        for (s in seed until seed + 60) {
            val dice = Dice(Random(s))
            val b = PartyBuilder(dice)
            b.drafts[0].charClass = cls
            val game = Game(b.build(), dice)
            val hero = game.party[0]
            hero.gainXp(Hero.XP_TABLE[level - 1], dice)
            hero.chooseSubclass(sub)
            game.party.drop(1).forEach { it.gainXp(Hero.XP_TABLE[level - 1], dice); Bot.manageParty(game) }
            var ticks = 0
            while (ticks++ < 20_000 && game.phase != Phase.GAME_OVER) {
                if (game.phase == Phase.PLAYER_TURN && game.current === hero && !hero.isUnconscious &&
                    game.monsters.any { !it.ally && game.dungeon.isVisible(it.x, it.y) && hero.distanceTo(it) <= 6 &&
                        game.dungeon.hasLineOfSight(hero.x, hero.y, it.x, it.y) } && ready(game, hero)
                ) return game to hero
                if (game.isBusy) game.tick() else if (game.phase == Phase.PLAYER_TURN && game.current === hero) {
                    game.perform(game.availableActions().first { it.id == ActionId.END_TURN })
                } else Bot.play(game, 1, maxDepth = 3)
            }
        }
        fail("no combat turn found for $sub")
    }

    private fun Game.foe(h: Hero) = monsters.filter {
        !it.ally && dungeon.isVisible(it.x, it.y) && h.distanceTo(it) <= 6 && dungeon.hasLineOfSight(h.x, h.y, it.x, it.y)
    }.minBy { h.distanceTo(it) }

    @Test
    fun knightChallengeImposesDisadvantageOnOthers() {
        val (game, knight) = combatTurn(CharClass.FIGHTER, Subclass.KNIGHT, 3)
        val foe = game.foe(knight)
        game.perform(game.availableActions().first { it.id == ActionId.CHALLENGE })
        game.tap(foe.x, foe.y)
        assertEquals(knight, foe.challenger)
        assertFalse(game.bonusActionAvailable)
    }

    @Test
    fun rangerMarkAndAssassinEnvenomTakeBonusActions() {
        val (game, ranger) = combatTurn(CharClass.ROGUE, Subclass.RANGER, 3)
        val foe = game.foe(ranger)
        game.perform(game.availableActions().first { it.id == ActionId.HUNTERS_MARK })
        game.tap(foe.x, foe.y)
        assertEquals(ranger, foe.huntedBy)

        val (g2, asn) = combatTurn(CharClass.ROGUE, Subclass.ASSASSIN, 3, seed = 5)
        g2.perform(g2.availableActions().first { it.id == ActionId.ENVENOM })
        assertTrue(asn.envenomed)
        assertEquals(1, asn.uses(Resource.ENVENOM))
    }

    @Test
    fun necromancerRaisesASkeletonThatFightsForTheParty() {
        val (game, necro) = combatTurn(CharClass.WIZARD, Subclass.NECROMANCER, 3, seed = 2) { g, h ->
            g.corpses.any { g.dungeon.isVisible(it.x, it.y) && chebyshev(it.x, it.y, h.x, h.y) <= 6 && !g.isOccupied(it.x, it.y) }
        }
        val corpse = game.corpses.first { game.dungeon.isVisible(it.x, it.y) && chebyshev(it.x, it.y, necro.x, necro.y) <= 6 && !game.isOccupied(it.x, it.y) }
        val slots = necro.spellSlots.sum()
        game.perform(game.availableActions().first { it.id == ActionId.RAISE_DEAD })
        game.tap(corpse.x, corpse.y)
        val skeleton = game.minions.singleOrNull { it.master === necro }
        assertNotNull(skeleton)
        assertTrue(skeleton.ally)
        assertEquals(slots - 1, necro.spellSlots.sum())
        assertTrue(skeleton in game.turnOrder)
        assertNull(game.enemyAt(skeleton.x, skeleton.y), "minions are not enemies")
        // Play on: skeleton turns must not crash and it never attacks heroes.
        repeat(400) { if (game.inCombat) Bot.play(game, 1, maxDepth = 3) }
        assertTrue(game.log.none { it.text.contains("(yours)") && it.text.contains(" on ${game.party[1].name}") })
    }

    @Test
    fun priestPreserveLifeHealsTheBadlyWounded() {
        val (game, priest) = combatTurn(CharClass.CLERIC, Subclass.PRIEST, 3, seed = 9)
        val allies = game.livingHeroes.filter { it !== priest && it.distanceTo(priest) <= 6 }
        if (allies.isEmpty()) return
        allies.forEach { it.hp = 1 }
        game.perform(game.availableActions().first { it.id == ActionId.PRESERVE_LIFE })
        // The pool (5 x level) goes to the most hurt first, never past half of anyone's max.
        val healed = allies.sumOf { it.hp - 1 }
        assertTrue(healed in 1..5 * priest.level)
        assertTrue(allies.all { it.hp <= maxOf(1, it.maxHp / 2) })
        assertEquals(0, priest.uses(Resource.PRESERVE_LIFE))
    }

    @Test
    fun mageOverchannelMaximisesDamage() {
        val (game, mage) = combatTurn(CharClass.WIZARD, Subclass.MAGE, 7, seed = 4)
        val foe = game.foe(mage)
        game.perform(game.availableActions().first { it.id == ActionId.OVERCHANNEL })
        assertTrue(mage.overchannelReady)
        val hp = foe.hp
        game.castSpell(mage, Spell.MAGIC_MISSILE, foe, null)
        // Darts of 1d4+1 maximised are 5 each, plus Arcane Potency once.
        val darts = 3 + (game.log.last { it.text.contains("casts Magic Missile") }.text.substringAfter("level ").substringBefore(" ").toInt() - 1)
        val expected = darts * 5 + maxOf(0, mage.spellMod)
        assertTrue(foe.isDead || hp - foe.hp == expected, "expected $expected, got ${hp - foe.hp}")
        assertFalse(mage.overchannelReady)
        assertEquals(0, mage.uses(Resource.OVERCHANNEL))
    }

    @Test
    fun paladinSmiteSpendsOnHit() {
        val (game, pal) = combatTurn(CharClass.FIGHTER, Subclass.PALADIN, 3, seed = 6) { g, h ->
            g.monsters.any { !it.ally && it.isAdjacentTo(h) }
        }
        val foe = game.monsters.first { !it.ally && it.isAdjacentTo(pal) }
        game.perform(game.availableActions().first { it.id == ActionId.SMITE })
        assertTrue(pal.smiteReady)
        game.tap(foe.x, foe.y)
        val hitLog = game.log.lastOrNull { it.text.contains("Divine Smite") }
        if (hitLog != null) {
            assertEquals(1, pal.uses(Resource.SMITE))
            assertFalse(pal.smiteReady)
        } else {
            assertEquals(2, pal.uses(Resource.SMITE), "a miss keeps the smite")
            assertTrue(pal.smiteReady)
        }
    }
}
