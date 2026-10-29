package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RulesTest {
    @Test
    fun abilityModifiersFollowTheTable() {
        assertEquals(-5, abilityModifier(1))
        assertEquals(-1, abilityModifier(8))
        assertEquals(-1, abilityModifier(9))
        assertEquals(0, abilityModifier(10))
        assertEquals(0, abilityModifier(11))
        assertEquals(3, abilityModifier(16))
        assertEquals(5, abilityModifier(20))
    }

    @Test
    fun diceExpressionsParseAndPrint() {
        assertEquals(DiceExpr(2, 6, 3), d("2d6+3"))
        assertEquals(DiceExpr(2, 6, -2), d("2d6-2"))
        assertEquals("1d8", d("1d8").toString())
        assertEquals("7d10+21", d("7d10+21").toString())
        assertEquals(59.5, d("7d10+21").average)
        assertFailsWith<IllegalArgumentException> { d("banana") }
    }

    @Test
    fun rollsStayInRange() {
        val dice = Dice(Random(1))
        repeat(2000) {
            assertTrue(dice.roll(d("2d6+3")) in 5..15)
            assertTrue(dice.abilityScore() in 3..18)
            assertTrue(dice.d20(RollMode.ADVANTAGE).natural in 1..20)
        }
    }

    @Test
    fun criticalHitsDoubleDiceButNotBonus() {
        assertEquals(4 * 6 + 3, ScriptedDice(6, 6, 6, 6).roll(d("2d6+3"), critical = true))
    }

    @Test
    fun advantageTakesHigherDisadvantageLower() {
        assertEquals(17, ScriptedDice(4, 17).d20(RollMode.ADVANTAGE).natural)
        assertEquals(4, ScriptedDice(4, 17).d20(RollMode.DISADVANTAGE).natural)
        assertEquals(RollMode.NORMAL, RollMode.of(advantage = true, disadvantage = true))
    }

    @Test
    fun luckyRerollsNaturalOnes() {
        assertEquals(15, ScriptedDice(1, 15).d20(lucky = true).natural)
        assertEquals(1, ScriptedDice(1, 15).d20(lucky = false).natural)
    }

    @Test
    fun fourD6DropsLowest() {
        assertEquals(15, ScriptedDice(1, 5, 5, 5).abilityScore())
    }

    @Test
    fun heroCreationAssignsByClassPriorityAndAddsRace() {
        val hero = Hero.create("Bruenor", Race.DWARF, CharClass.FIGHTER, listOf(8, 15, 10, 14, 12, 13))
        assertEquals(15, hero.abilities[Ability.STR])
        assertEquals(16, hero.abilities[Ability.CON]) // 14 + 2
        assertEquals(13, hero.abilities[Ability.DEX])
        // d10 + CON(+3) + dwarven toughness
        assertEquals(14, hero.maxHp)
        assertEquals(18, hero.armorClass) // chain mail + shield, no DEX
        assertEquals(2, hero.proficiency)
        assertEquals(4, hero.attackBonus(Weapons.LONGSWORD)) // STR +2, prof +2
    }

    @Test
    fun rogueUsesDexForFinesseAndLightArmor() {
        val hero = Hero.create("Vex", Race.HALFLING, CharClass.ROGUE, listOf(15, 14, 13, 12, 10, 8))
        assertEquals(17, hero.abilities[Ability.DEX])
        assertEquals(Ability.DEX, hero.abilityFor(Weapons.SHORTSWORD))
        assertEquals(14, hero.armorClass) // 11 + 3
        assertEquals(5, hero.saveBonus(Ability.DEX)) // proficient
        assertEquals(1, hero.saveBonus(Ability.WIS)) // 13, not proficient
    }

    @Test
    fun levellingRaisesHpProficiencyAndSlots() {
        val hero = Hero.create("Elminster", Race.HUMAN, CharClass.WIZARD, listOf(16, 14, 13, 12, 10, 8))
        assertEquals(listOf(2), hero.spellSlots.toList())
        val startHp = hero.maxHp
        val msgs = hero.gainXp(2700, Dice(Random(0)))
        assertEquals(3, msgs.size)
        assertEquals(4, hero.level)
        assertEquals(listOf(4, 3), hero.spellSlots.toList())
        assertEquals(startHp + 3 * (4 + 2), hero.maxHp) // fixed d6 gain 4 + CON +2 (14+1=15)
        hero.gainXp(6500, Dice(Random(0)))
        assertEquals(3, hero.proficiency)
        assertEquals(2, hero.cantripDice)
    }

    @Test
    fun spentSlotsStaySpentOnLevelUp() {
        val hero = Hero.create("Cleo", Race.HUMAN, CharClass.CLERIC, listOf(16, 14, 13, 12, 10, 8))
        hero.spendSlot(1)
        hero.spendSlot(1)
        hero.gainXp(300, Dice(Random(0)))
        assertEquals(listOf(1), hero.spellSlots.toList()) // gained the one new slot only
        hero.longRest()
        assertEquals(listOf(3), hero.spellSlots.toList())
    }

    @Test
    fun healingFromZeroClearsDeathSaves() {
        val hero = Hero.create("Pip", Race.HALFLING, CharClass.ROGUE, listOf(15, 14, 13, 12, 10, 8))
        hero.hp = 0
        hero.deathFailures = 2
        assertTrue(hero.isUnconscious)
        assertEquals(5, hero.heal(5))
        assertEquals(0, hero.deathFailures)
    }

    @Test
    fun monsterStatsMatchTheirBlocks() {
        val ogre = Monster(MonsterType.OGRE, ScriptedDice(10, 10, 10, 10, 10, 10, 10))
        assertEquals(91, ogre.maxHp)
        assertEquals(-1, ogre.saveBonus(Ability.DEX))
        assertEquals(MonsterType.GOBLIN.melee.name, "Scimitar")
        assertEquals(8, MonsterType.GOBLIN.rangedAttack!!.range)
        assertEquals(1, Monster(MonsterType.KOBOLD, ScriptedDice(1, 1)).maxHp) // 2d6-2 floors at 1
    }
}
