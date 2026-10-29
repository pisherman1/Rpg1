package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelLootTest {
    private fun hero(cls: CharClass, race: Race = Race.HUMAN) = Hero.create("T", race, cls, listOf(15, 14, 13, 12, 10, 8))

    @Test
    fun abilityImprovementsArriveAtFourAndEight() {
        val f = hero(CharClass.FIGHTER)
        f.gainXp(Hero.XP_TABLE[2], Dice(Random(1)))
        assertEquals(0, f.pendingAbilityPoints)
        f.gainXp(Hero.XP_TABLE[3] - f.xp, Dice(Random(1)))
        assertEquals(4, f.level)
        assertEquals(2, f.pendingAbilityPoints)
        f.gainXp(Hero.XP_TABLE[7] - f.xp, Dice(Random(1)))
        assertEquals(8, f.level)
        assertEquals(6, f.pendingAbilityPoints) // 4, 6 (fighter bonus) and 8
    }

    @Test
    fun improvementsAreValidatedAndConRaisesHpRetroactively() {
        val w = hero(CharClass.WIZARD) // CON 14 (+2 human) -> 15? priority INT, CON: 15/14 -> +1 = 16/15
        w.gainXp(Hero.XP_TABLE[3], Dice(Random(1)))
        assertEquals(4, w.level)
        val maxBefore = w.maxHp
        w.hp -= 5
        assertFalse(w.improveAbilities(mapOf(Ability.INT to 3)), "only 2 points available")
        assertFalse(w.improveAbilities(emptyMap()))
        assertTrue(w.improveAbilities(mapOf(Ability.CON to 1, Ability.INT to 1)))
        assertEquals(0, w.pendingAbilityPoints)
        // CON 15 -> 16: modifier +2 -> +3, one HP per level.
        assertEquals(maxBefore + 4, w.maxHp)
        assertEquals(w.maxHp - 5, w.hp)
    }

    @Test
    fun abilityScoresCapAtTwenty() {
        val f = Hero.withScores("Max", Race.HUMAN, CharClass.FIGHTER, listOf(19, 10, 10, 10, 10, 10))
        f.gainXp(Hero.XP_TABLE[3], Dice(Random(1)))
        assertFalse(f.improveAbilities(mapOf(Ability.STR to 2)))
        assertTrue(f.improveAbilities(mapOf(Ability.STR to 0, Ability.CON to 2)))
    }

    @Test
    fun castersLearnSpellsGatedBySlotLevel() {
        val w = hero(CharClass.WIZARD)
        assertEquals(CharClass.WIZARD.startingSpells, w.knownSpells)
        w.gainXp(Hero.XP_TABLE[1], Dice(Random(1)))
        assertEquals(1, w.pendingSpells)
        assertTrue(w.learnableSpells().none { it.level > 1 }, "no 2nd-level spells at level 2")
        assertFalse(w.learnSpell(Spell.FIREBALL))
        assertTrue(w.learnSpell(Spell.SLEEP))
        assertFalse(w.learnSpell(Spell.RAY_OF_FROST), "no picks left")
        w.gainXp(Hero.XP_TABLE[4] - w.xp, Dice(Random(1)))
        assertTrue(Spell.FIREBALL in w.learnableSpells())
        // Martial classes never get spell picks.
        val r = hero(CharClass.ROGUE)
        r.gainXp(Hero.XP_TABLE[5], Dice(Random(1)))
        assertEquals(0, r.pendingSpells)
    }

    @Test
    fun itemsChangeTheNumbers() {
        val f = hero(CharClass.FIGHTER)
        val ac = f.armorClass
        val atk = f.attackBonus(Weapons.LONGSWORD)
        f.equip(Item(ItemType.MAGIC_WEAPON, 2))
        f.equip(Item(ItemType.MAGIC_ARMOR, 1))
        f.equip(Item(ItemType.RING_OF_PROTECTION))
        assertEquals(ac + 2, f.armorClass)
        assertEquals(atk + 2, f.attackBonus(Weapons.LONGSWORD))
        assertEquals(f.abilities.mod(Ability.CON) + 2 + 1, f.saveBonus(Ability.CON))
        val speed = f.speed
        f.equip(Item(ItemType.BOOTS_OF_STRIDING))
        assertEquals(speed + 1, f.speed)
        // A wand does nothing for a fighter.
        f.equip(Item(ItemType.WAND_OF_THE_WAR_MAGE, 3))
        assertEquals(0, f.focusBonus)
    }

    @Test
    fun amuletOfHealthRaisesConAndHpKeepsDamage() {
        val r = hero(CharClass.ROGUE)
        r.gainXp(Hero.XP_TABLE[3], Dice(Random(1)))
        val maxBefore = r.maxHp
        r.hp = maxBefore - 3
        val conModBefore = r.abilities.mod(Ability.CON)
        r.equip(Item(ItemType.AMULET_OF_HEALTH))
        assertEquals(19, r.abilities[Ability.CON])
        assertEquals(maxBefore + (4 - conModBefore) * r.level, r.maxHp)
        assertEquals(r.maxHp - 3, r.hp)
        r.unequip(ItemSlot.NECK)
        assertEquals(maxBefore, r.maxHp)
        assertEquals(maxBefore - 3, r.hp)
    }

    @Test
    fun equipSwapsThroughTheBagOutsideCombatOnly() {
        val dice = Dice(Random(5))
        val game = Game(PartyBuilder(dice).build(), dice)
        val f = game.party.first()
        val sword = Item(ItemType.MAGIC_WEAPON, 1)
        val better = Item(ItemType.MAGIC_WEAPON, 2)
        game.bag += sword
        game.bag += better
        assertTrue(game.equip(f, sword))
        assertTrue(game.equip(f, better))
        assertEquals(listOf(sword), game.bag)
        assertEquals(better, f.item(ItemSlot.WEAPON))
        assertTrue(game.unequip(f, ItemSlot.WEAPON))
        assertNull(f.item(ItemSlot.WEAPON))
        assertEquals(2, game.bag.size)
    }

    @Test
    fun lootRollsBetterDeeper() {
        val dice = Dice(Random(3))
        val shallow = (1..400).map { Loot.randomItem(dice, 1) }
        val deep = (1..400).map { Loot.randomItem(dice, 10) }
        assertTrue(shallow.none { it.rarity == Rarity.VERY_RARE })
        assertTrue(deep.count { it.rarity != Rarity.UNCOMMON } > shallow.count { it.rarity != Rarity.UNCOMMON })
        assertTrue(Loot.all.all { it.name.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun extraAttackLetsFightersSwingTwice() {
        val f = hero(CharClass.FIGHTER)
        assertEquals(1, f.attacksPerAction)
        f.gainXp(Hero.XP_TABLE[4], Dice(Random(1)))
        assertEquals(2, f.attacksPerAction)
        assertTrue(hero(CharClass.ROGUE).also { it.gainXp(Hero.XP_TABLE[4], Dice(Random(1))) }.hasUncannyDodge)
    }

    @Test
    fun revivifyBringsBackTheDeadForGold() {
        val dice = Dice(Random(8))
        val b = PartyBuilder(dice)
        b.drafts[0].charClass = CharClass.CLERIC
        val game = Game(b.build(), dice)
        val cleric = game.party[0]
        cleric.gainXp(Hero.XP_TABLE[4], dice)
        cleric.knownSpells += Spell.REVIVIFY
        val fallen = game.party[1]
        fallen.hp = 0
        fallen.dead = true
        assertFalse(game.canCastNow(cleric, Spell.REVIVIFY), "needs gold")
        game.party[2].gold = 350
        assertTrue(game.canCastNow(cleric, Spell.REVIVIFY))
        game.castSpell(cleric, Spell.REVIVIFY, null, fallen)
        assertFalse(fallen.dead)
        assertEquals(1, fallen.hp)
        assertEquals(50, game.partyGold)
        assertNotNull(game.heroAt(fallen.x, fallen.y))
    }

    @Test
    fun savesKeepLevelUpsEquipmentAndBag() {
        val dice = Dice(Random(9))
        val game = Game(PartyBuilder(dice).build(), dice)
        val h = game.party[2]
        h.gainXp(Hero.XP_TABLE[3], dice)
        h.equip(Item(ItemType.CLOAK_OF_PROTECTION))
        game.bag += Item(ItemType.MAGIC_ARMOR, 2)
        val loaded = Game.fromJson(game.toJson())
        val lh = loaded.party[2]
        assertEquals(h.level, lh.level)
        assertEquals(h.pendingAbilityPoints, lh.pendingAbilityPoints)
        assertEquals(h.pendingSpells, lh.pendingSpells)
        assertEquals(h.knownSpells, lh.knownSpells)
        assertEquals(h.armorClass, lh.armorClass)
        assertEquals(h.maxHp, lh.maxHp)
        assertEquals(listOf(Item(ItemType.MAGIC_ARMOR, 2)), loaded.bag)
    }

    @Test
    fun versionOneSavesStillLoad() {
        val dice = Dice(Random(4))
        val game = Game(PartyBuilder(dice).build(), dice)
        @Suppress("UNCHECKED_CAST")
        val map = Json.parse(game.toJson()) as MutableMap<String, Any?>
        map["version"] = 1L
        map.remove("bag")
        map["heroes"] = (map["heroes"] as List<Map<String, Any?>>).mapIndexed { i, h ->
            h.filterKeys { it !in setOf("spells", "pendingAbilityPoints", "pendingSpells", "equipment", "reaction", "blessed") } +
                mapOf("weaponBonus" to (if (i == 0) 2L else 0L), "armorBonus" to 1L, "maxHp" to 10L)
        }
        val loaded = Game.fromSave(map)
        assertEquals(Item(ItemType.MAGIC_WEAPON, 2), loaded.party[0].item(ItemSlot.WEAPON))
        assertEquals(1, loaded.party[1].armorBonus)
        assertEquals(loaded.party[0].charClass.startingSpells, loaded.party[0].knownSpells)
    }
}
