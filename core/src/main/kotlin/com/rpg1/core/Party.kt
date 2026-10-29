package com.rpg1.core

const val PARTY_SIZE = 4

/**
 * A premade hero. [baseScores] are STR, DEX, CON, INT, WIS, CHA before racial bonuses;
 * leave it null to roll 4d6-drop-lowest when the hero is picked.
 */
data class HeroTemplate(
    val name: String,
    val race: Race,
    val charClass: CharClass,
    val baseScores: List<Int>? = null,
    val gender: Gender = Gender.MALE,
) {
    init {
        require(baseScores == null || baseScores.size == 6) { "baseScores needs 6 values" }
    }
}

data class PartyTemplate(val name: String, val members: List<HeroTemplate>) {
    init {
        require(members.size == PARTY_SIZE) { "A party has $PARTY_SIZE members" }
    }
}

/**
 * Premade heroes and parties offered on the party screen.
 * Add your own variants here; they show up in the game automatically.
 */
object Premades {
    val heroes: List<HeroTemplate> = listOf(
        HeroTemplate("Brakka", Race.DWARF, CharClass.FIGHTER, listOf(15, 12, 14, 8, 13, 10), Gender.FEMALE),
        HeroTemplate("Pip", Race.HALFLING, CharClass.ROGUE, listOf(10, 15, 14, 12, 13, 8), Gender.MALE),
        HeroTemplate("Ilyra", Race.ELF, CharClass.WIZARD, listOf(8, 14, 13, 15, 12, 10), Gender.FEMALE),
        HeroTemplate("Aldric", Race.HUMAN, CharClass.CLERIC, listOf(13, 10, 14, 8, 15, 12), Gender.MALE),
    )

    val parties: List<PartyTemplate> = listOf(
        PartyTemplate("The Classic Four", heroes),
    )
}

/** One slot on the party screen: either a premade or a custom build. */
class HeroDraft(var name: String, var race: Race, var charClass: CharClass, var rolled: List<Int>, var gender: Gender = Gender.MALE) {
    /** Fixed scores from a premade; null means use [rolled]. */
    var fixedScores: List<Int>? = null

    fun build(): Hero = fixedScores?.let { Hero.withScores(name, race, charClass, it, gender) }
        ?: Hero.create(name, race, charClass, rolled, gender)

    fun reroll(dice: Dice) {
        rolled = List(6) { dice.abilityScore() }
        fixedScores = null
    }

    fun apply(t: HeroTemplate, dice: Dice) {
        name = t.name
        race = t.race
        charClass = t.charClass
        gender = t.gender
        if (t.baseScores != null) fixedScores = t.baseScores else reroll(dice)
    }
}

class PartyBuilder(private val dice: Dice = Dice()) {
    val drafts: List<HeroDraft> = List(PARTY_SIZE) { i ->
        val gender = if (i % 2 == 0) Gender.MALE else Gender.FEMALE
        HeroDraft(
            randomName(gender),
            Race.entries[i % Race.entries.size],
            CharClass.entries[i % CharClass.entries.size],
            List(6) { dice.abilityScore() },
            gender,
        )
    }

    /** Switches a hero's gender; a default name is swapped for one that fits. */
    fun toggleGender(slot: Int) {
        val d = drafts[slot]
        val wasDefault = d.name in namesFor(d.gender)
        d.gender = d.gender.other
        if (wasDefault) d.name = randomName(d.gender)
    }

    fun applyParty(t: PartyTemplate) = t.members.forEachIndexed { i, m -> drafts[i].apply(m, dice) }

    fun applyHero(slot: Int, t: HeroTemplate) = drafts[slot].apply(t, dice)

    fun reroll(slot: Int) = drafts[slot].reroll(dice)

    fun randomName(gender: Gender = Gender.MALE): String = dice.pick(namesFor(gender))

    fun build(): List<Hero> = drafts.map { it.build() }

    companion object {
        val MALE_NAMES = listOf("Garrick", "Fenwick", "Orrin", "Dunmore", "Tobin", "Corvin", "Bram", "Halvard", "Aldous", "Rorik")
        val FEMALE_NAMES = listOf("Thora", "Mira", "Sable", "Kestrel", "Lyra", "Wren", "Isolde", "Nessa", "Brenna", "Elowen")

        fun namesFor(gender: Gender) = if (gender == Gender.MALE) MALE_NAMES else FEMALE_NAMES
    }
}
