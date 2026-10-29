package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PartyTest {
    @Test
    fun premadesAreValid() {
        Premades.parties.forEach { assertEquals(PARTY_SIZE, it.members.size) }
        Premades.heroes.forEach { t -> t.baseScores?.let { assertEquals(6, it.size) } }
    }

    @Test
    fun builderStartsWithFourDistinctDraftsAndBuildsHeroes() {
        val builder = PartyBuilder(Dice(Random(1)))
        assertEquals(PARTY_SIZE, builder.drafts.size)
        assertEquals(CharClass.entries.toSet(), builder.drafts.map { it.charClass }.toSet())
        val heroes = builder.build()
        assertEquals(PARTY_SIZE, heroes.size)
    }

    @Test
    fun premadeScoresAreUsedExactlyPlusRacialBonus() {
        val builder = PartyBuilder(Dice(Random(1)))
        builder.applyParty(Premades.parties.first())
        val brakka = builder.build().first()
        assertEquals("Brakka", brakka.name)
        assertEquals(Race.DWARF, brakka.race)
        assertEquals(16, brakka.abilities[Ability.CON]) // 14 + 2
        assertEquals(15, brakka.abilities[Ability.STR])
    }

    @Test
    fun customDraftCanBeEditedAndRerolled() {
        val builder = PartyBuilder(Dice(Random(9)))
        val draft = builder.drafts[0]
        builder.applyHero(0, Premades.heroes[2])
        draft.name = "Zed"
        draft.race = Race.HALFLING
        draft.charClass = CharClass.ROGUE
        builder.reroll(0)
        val hero = draft.build()
        assertEquals("Zed", hero.name)
        assertEquals(CharClass.ROGUE, hero.charClass)
        // Rerolling drops the premade's fixed scores and assigns the best roll to DEX.
        assertEquals(draft.rolled.max() + 2, hero.abilities[Ability.DEX])
        val old = draft.rolled
        repeat(5) { builder.reroll(0) }
        assertNotEquals(old, draft.rolled)
    }
}
