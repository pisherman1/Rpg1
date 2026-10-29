package com.rpg1.core

class Hero(
    name: String,
    val race: Race,
    val charClass: CharClass,
    /** Final scores, racial bonuses already applied. */
    override val abilities: AbilityScores,
) : Creature(name) {
    var level = 1
        private set
    var xp = 0
        private set

    override var maxHp: Int = charClass.hitDie + conMod + toughness
        private set
    override var hp: Int = maxHp

    var hitDice = 1
    var spellSlots: IntArray = if (charClass.isCaster) SpellSlots.forLevel(1) else IntArray(0)
        private set
    var maxSpellSlots: IntArray = spellSlots.copyOf()
        private set

    var potions = 2
    var gold = 0
    /** +N magic weapon / armor found in chests. */
    var weaponBonus = 0
    var armorBonus = 0

    var secondWindAvailable = true
    var actionSurgeAvailable = true

    var deathSuccesses = 0
    var deathFailures = 0
    var stable = false
    var dead = false

    val isUnconscious get() = hp <= 0 && !dead

    private val conMod get() = abilities.mod(Ability.CON)
    private val toughness get() = if (race == Race.DWARF) 1 else 0

    val proficiency get() = 2 + (level - 1) / 4

    override val speed get() = race.speed

    override val armorClass get() = charClass.armor.armorClass(abilities.mod(Ability.DEX)) + armorBonus

    val lucky get() = race == Race.HALFLING

    override fun saveBonus(ability: Ability) =
        abilities.mod(ability) + if (ability in charClass.savingThrows) proficiency else 0

    fun abilityFor(weapon: Weapon): Ability = when {
        weapon.ranged -> Ability.DEX
        weapon.finesse && abilities.mod(Ability.DEX) > abilities.mod(Ability.STR) -> Ability.DEX
        else -> Ability.STR
    }

    fun attackBonus(weapon: Weapon) = abilities.mod(abilityFor(weapon)) + proficiency + weaponBonus

    fun damageBonus(weapon: Weapon) = abilities.mod(abilityFor(weapon)) + weaponBonus

    val spellMod get() = charClass.spellAbility?.let { abilities.mod(it) } ?: 0
    val spellAttackBonus get() = spellMod + proficiency
    val spellSaveDc get() = 8 + spellMod + proficiency

    /** Rogue sneak attack dice: 1d6 at 1st level, +1d6 every odd level. */
    val sneakAttackDice get() = (level + 1) / 2

    /** Cantrips gain a die at 5th level (and 11th, beyond our cap). */
    val cantripDice get() = if (level >= 5) 2 else 1

    val xpForNextLevel: Int? get() = XP_TABLE.getOrNull(level)

    val canCast get() = spellSlots.any { it > 0 }

    /** Lowest spell slot level with a slot left, or null. */
    fun lowestAvailableSlot(): Int? = spellSlots.indexOfFirst { it > 0 }.takeIf { it >= 0 }?.plus(1)

    fun spendSlot(slotLevel: Int) {
        check(spellSlots[slotLevel - 1] > 0)
        spellSlots[slotLevel - 1]--
    }

    /** Adds XP and returns a message for each level gained. */
    fun gainXp(amount: Int, dice: Dice): List<String> {
        xp += amount
        val messages = mutableListOf<String>()
        while (level < MAX_LEVEL && xp >= XP_TABLE[level]) {
            level++
            val gain = maxOf(1, charClass.hitDie / 2 + 1 + conMod) + toughness
            maxHp += gain
            hp = if (hp > 0) hp + gain else hp
            hitDice++
            if (charClass.isCaster) {
                val newMax = SpellSlots.forLevel(level)
                val oldMax = maxSpellSlots
                spellSlots = IntArray(newMax.size) { i ->
                    (spellSlots.getOrElse(i) { 0 }) + newMax[i] - oldMax.getOrElse(i) { 0 }
                }
                maxSpellSlots = newMax
            }
            messages += "${name} reaches level $level! (+$gain max HP)"
        }
        return messages
    }

    fun heal(amount: Int): Int {
        if (dead) return 0
        val before = hp.coerceAtLeast(0)
        hp = minOf(maxHp, before + amount)
        if (hp > 0) {
            deathSuccesses = 0
            deathFailures = 0
            stable = false
        }
        return hp - before
    }

    fun longRest() {
        hp = maxHp
        hitDice = minOf(level, hitDice + maxOf(1, level / 2))
        spellSlots = maxSpellSlots.copyOf()
        secondWindAvailable = true
        actionSurgeAvailable = true
        deathSuccesses = 0
        deathFailures = 0
        stable = false
    }

    /** Spends hit dice until healed or out of dice; recovers short-rest features. Returns HP healed. */
    fun shortRest(dice: Dice): Int {
        var healed = 0
        while (hitDice > 0 && hp < maxHp) {
            hitDice--
            healed += heal(maxOf(1, dice.roll(charClass.hitDie) + conMod))
        }
        secondWindAvailable = true
        actionSurgeAvailable = true
        return healed
    }

    companion object {
        const val MAX_LEVEL = 10
        val XP_TABLE = intArrayOf(0, 300, 900, 2700, 6500, 14000, 23000, 34000, 48000, 64000)

        /** Rolled scores are assigned to abilities by class priority, then racial bonuses applied. */
        fun create(name: String, race: Race, charClass: CharClass, rolled: List<Int>): Hero =
            Hero(name, race, charClass, AbilityScores.assign(rolled, charClass.abilityPriority).withBonuses(race.bonuses))

        /** [baseScores] in STR, DEX, CON, INT, WIS, CHA order, before racial bonuses. */
        fun withScores(name: String, race: Race, charClass: CharClass, baseScores: List<Int>): Hero =
            Hero(name, race, charClass, AbilityScores.of(*baseScores.toIntArray()).withBonuses(race.bonuses))
    }
}
