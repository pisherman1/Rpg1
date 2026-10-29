package com.rpg1.core

import java.util.EnumMap

class Hero(
    name: String,
    val race: Race,
    val charClass: CharClass,
    /** Scores with racial bonuses and ability score improvements, before magic items. */
    baseAbilities: AbilityScores,
    val gender: Gender = Gender.MALE,
) : Creature(name) {
    var baseAbilities: AbilityScores = baseAbilities
        private set

    var level = 1
        private set
    var xp = 0
        private set

    val equipment: MutableMap<ItemSlot, Item> = EnumMap(ItemSlot::class.java)

    /** Effective scores: base scores, raised by items such as an Amulet of Health. */
    override val abilities: AbilityScores
        get() {
            val sets = equipment.values.mapNotNull { it.setsAbility }
            if (sets.isEmpty()) return baseAbilities
            return AbilityScores(Ability.entries.associateWith { a ->
                maxOf(baseAbilities[a], sets.filter { it.first == a }.maxOfOrNull { it.second } ?: 0)
            })
        }

    /** Fixed hit points per level (5e average), plus CON for every level, so CON changes apply retroactively. */
    override val maxHp: Int
        get() = maxOf(level, charClass.hitDie + (level - 1) * (charClass.hitDie / 2 + 1) + level * (conMod + toughness))
    override var hp: Int = 0

    var hitDice = 1
    var spellSlots: IntArray = if (charClass.isCaster) SpellSlots.forLevel(1) else IntArray(0)
        private set
    var maxSpellSlots: IntArray = spellSlots.copyOf()
        private set

    /** Spells this hero can cast. */
    val knownSpells: MutableList<Spell> = charClass.startingSpells.toMutableList()

    /** Level-up choices still to make. */
    var pendingAbilityPoints = 0
        private set
    var pendingSpells = 0
        private set
    val hasPendingChoices get() = pendingAbilityPoints > 0 || pendingSpells > 0 || needsSubclass

    // --- Subclass ------------------------------------------------------------

    var subclass: Subclass? = null
        private set
    val needsSubclass get() = level >= SUBCLASS_LEVEL && subclass == null

    fun has(feature: Feature) = subclass == feature.subclass && level >= feature.level

    /** Remaining uses of limited features; absent means full. */
    private val resourcesUsed: MutableMap<Resource, Int> = EnumMap(Resource::class.java)

    fun maxUses(r: Resource): Int = when (r) {
        Resource.SMITE -> if (has(Feature.DIVINE_SMITE)) (if (level >= 7) 3 else 2) else 0
        Resource.LAY_ON_HANDS -> if (has(Feature.LAY_ON_HANDS)) 5 * level else 0
        Resource.ENVENOM -> if (has(Feature.ENVENOM)) 2 else 0
        Resource.PRESERVE_LIFE -> if (has(Feature.PRESERVE_LIFE)) 1 else 0
        Resource.DESTRUCTIVE_WRATH -> if (has(Feature.DESTRUCTIVE_WRATH)) 1 else 0
        Resource.WRATH_OF_THE_STORM -> if (has(Feature.WRATH_OF_THE_STORM)) maxOf(1, abilities.mod(Ability.WIS)) else 0
        Resource.OVERCHANNEL -> if (has(Feature.OVERCHANNEL)) 1 else 0
        Resource.ARCANE_RECOVERY -> if (has(Feature.ARCANE_RECOVERY)) 1 else 0
        Resource.INDOMITABLE -> if (charClass == CharClass.FIGHTER && level >= 9) 1 else 0
    }

    fun uses(r: Resource) = (maxUses(r) - (resourcesUsed[r] ?: 0)).coerceAtLeast(0)

    fun spend(r: Resource, amount: Int = 1): Boolean {
        if (uses(r) < amount) return false
        resourcesUsed[r] = (resourcesUsed[r] ?: 0) + amount
        return true
    }

    /** Readied abilities that trigger on the next suitable hit or spell. */
    var smiteReady = false
    var envenomed = false
    var wrathReady = false
    var overchannelReady = false

    fun chooseSubclass(choice: Subclass): Boolean {
        if (!needsSubclass || choice.charClass != charClass) return false
        subclass = choice
        grantSubclassSpells()
        return true
    }

    /** Domain spells that come with a subclass, known for free. */
    private fun grantSubclassSpells() {
        val grants = when (subclass) {
            Subclass.PRIEST -> listOf(Spell.BLESS to 3, Spell.REVIVIFY to 5)
            Subclass.TEMPEST -> listOf(Spell.THUNDERWAVE to 3, Spell.CALL_LIGHTNING to 5)
            else -> emptyList()
        }
        for ((spell, lvl) in grants) if (level >= lvl && spell !in knownSpells) knownSpells += spell
    }

    /** The bow (or crossbow) this hero shoots with; rangers upgrade to a longbow. */
    val rangedWeapon: Weapon? get() = if (has(Feature.ARCHERY)) Weapons.LONGBOW else charClass.rangedWeapon

    var potions = 2
    var gold = 0

    var secondWindAvailable = true
    var actionSurgeAvailable = true
    /** Used by Uncanny Dodge; refreshed at the start of the hero's turn. */
    var reactionAvailable = true
    /** Bless: +1d4 to attack rolls until the fight ends. */
    var blessed = false

    var deathSuccesses = 0
    var deathFailures = 0
    var stable = false
    var dead = false

    init {
        hp = maxHp
    }

    val isUnconscious get() = hp <= 0 && !dead

    private val conMod get() = abilities.mod(Ability.CON)
    private val toughness get() = if (race == Race.DWARF) 1 else 0

    val proficiency get() = 2 + (level - 1) / 4

    fun item(slot: ItemSlot): Item? = equipment[slot]
    private fun has(type: ItemType) = equipment.values.any { it.type == type }
    private fun bonusIn(slot: ItemSlot) = equipment[slot]?.takeIf { it.usableBy(this) }?.bonus ?: 0

    val weaponBonus get() = bonusIn(ItemSlot.WEAPON)
    val armorBonus get() = bonusIn(ItemSlot.ARMOR)
    val focusBonus get() = bonusIn(ItemSlot.FOCUS)

    /** Ring and Cloak of Protection each add +1 to AC and saving throws. */
    private val protection get() = equipment.values.count {
        it.type == ItemType.RING_OF_PROTECTION || it.type == ItemType.CLOAK_OF_PROTECTION
    }

    val hasWoundClosure get() = has(ItemType.PERIAPT_OF_WOUND_CLOSURE)

    override val speed get() = race.speed + if (has(ItemType.BOOTS_OF_STRIDING)) 1 else 0

    override val armorClass get() = charClass.armor.armorClass(abilities.mod(Ability.DEX)) + armorBonus + protection +
        (if (has(Feature.SHIELD_WALL)) 1 else 0) + (if (evasive) 1 else 0)

    val lucky get() = race == Race.HALFLING

    override fun saveBonus(ability: Ability) =
        abilities.mod(ability) + (if (ability in charClass.savingThrows) proficiency else 0) + protection

    fun abilityFor(weapon: Weapon): Ability = when {
        weapon.ranged -> Ability.DEX
        weapon.finesse && abilities.mod(Ability.DEX) > abilities.mod(Ability.STR) -> Ability.DEX
        else -> Ability.STR
    }

    fun attackBonus(weapon: Weapon) = abilities.mod(abilityFor(weapon)) + proficiency + weaponBonus +
        if (weapon.ranged && has(Feature.ARCHERY)) 2 else 0

    fun damageBonus(weapon: Weapon) = abilities.mod(abilityFor(weapon)) + weaponBonus

    val spellMod get() = charClass.spellAbility?.let { abilities.mod(it) } ?: 0
    val spellAttackBonus get() = spellMod + proficiency + focusBonus
    val spellSaveDc get() = 8 + spellMod + proficiency + focusBonus

    /** Rogue sneak attack dice: 1d6 at 1st level, +1d6 every odd level. */
    val sneakAttackDice get() = (level + 1) / 2

    /** Cantrips gain a die at 5th level (and 11th, beyond our cap). */
    val cantripDice get() = if (level >= 5) 2 else 1

    /** Fighters attack twice per Attack action from 5th level. */
    val attacksPerAction get() = when {
        charClass != CharClass.FIGHTER -> 1
        level >= 10 -> 3
        level >= 5 -> 2
        else -> 1
    }

    /** Rogues learn to slip blows from 6th level. */
    private val evasive get() = charClass == CharClass.ROGUE && level >= 6

    /** Max HP gained per level after the first. */
    val hpPerLevel get() = maxOf(1, charClass.hitDie / 2 + 1 + conMod + toughness)

    fun isAbilityImprovementLevel(lvl: Int) = lvl in ASI_LEVELS ||
        (charClass == CharClass.FIGHTER && lvl == 6) || (charClass == CharClass.ROGUE && lvl == 10)

    /** Everything a hero gains on reaching [lvl] (besides hit points), in words. */
    fun levelGains(lvl: Int): List<String> {
        val gains = mutableListOf<String>()
        if (lvl == SUBCLASS_LEVEL) gains += if (subclass == null) "Choose a subclass" else "${subclass!!.displayName} path"
        subclass?.features?.filter { it.level == lvl && lvl != SUBCLASS_LEVEL }?.forEach { gains += it.title }
        if (subclass == null && lvl == 7) gains += "Subclass feature"
        if (lvl == SUBCLASS_LEVEL) subclass?.features?.filter { it.level == lvl }?.forEach { gains += it.title }
        when (charClass) {
            CharClass.FIGHTER -> when (lvl) {
                2 -> gains += "Action Surge: take an extra action once per rest"
                5 -> gains += "Extra Attack: attack twice per Attack action"
                9 -> gains += "Indomitable: reroll your first missed attack each long rest"
                10 -> gains += "Extra Attack (2): attack three times per Attack action"
            }
            CharClass.ROGUE -> {
                when (lvl) {
                    2 -> gains += "Cunning Action: Dash or Disengage as a bonus action"
                    5 -> gains += "Uncanny Dodge: halve the damage of a hit once per round"
                    6 -> gains += "Evasive: +1 AC"
                }
                if (lvl % 2 == 1 && lvl > 1) gains += "Sneak Attack ${(lvl + 1) / 2}d6"
            }
            CharClass.WIZARD, CharClass.CLERIC -> {
                val oldSlots = SpellSlots.forLevel(lvl - 1)
                val newSlots = SpellSlots.forLevel(lvl)
                if (newSlots.size > oldSlots.size) gains += "${ordinal(newSlots.size)}-level spell slots"
                else gains += "Spell slots ${newSlots.joinToString("/")}"
                if (lvl == 5) gains += "Cantrips deal double dice"
                gains += "Learn a new spell"
            }
        }
        if (isAbilityImprovementLevel(lvl)) gains += "Ability Score Improvement (+2)"
        if (lvl == 5 || lvl == 9) gains += "Proficiency bonus +${2 + (lvl - 1) / 4}"
        return gains
    }

    private fun ordinal(n: Int) = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }

    val hasUncannyDodge get() = charClass == CharClass.ROGUE && level >= 5

    val xpForNextLevel: Int? get() = XP_TABLE.getOrNull(level)

    val canCast get() = spellSlots.any { it > 0 }

    /** Highest spell level this hero has slots for. */
    val maxSpellLevel get() = maxSpellSlots.size

    /** Lowest slot level at or above [minLevel] with a slot left, or null. */
    fun lowestAvailableSlot(minLevel: Int = 1): Int? =
        (maxOf(1, minLevel)..spellSlots.size).firstOrNull { spellSlots[it - 1] > 0 }

    fun canCast(spell: Spell) = spell in knownSpells && (spell.isCantrip || lowestAvailableSlot(spell.level) != null)

    /** Restores one spent slot of [slotLevel]; false if none are spent. */
    fun recoverSlot(slotLevel: Int): Boolean {
        if (slotLevel !in 1..spellSlots.size || spellSlots[slotLevel - 1] >= maxSpellSlots[slotLevel - 1]) return false
        spellSlots[slotLevel - 1]++
        return true
    }

        fun spendSlot(slotLevel: Int) {
        check(spellSlots[slotLevel - 1] > 0)
        spellSlots[slotLevel - 1]--
    }

    // --- Levelling -----------------------------------------------------------

    /** Spells the hero could learn right now. */
    fun learnableSpells(): List<Spell> =
        charClass.spellList.filter { it !in knownSpells && it.level <= maxSpellLevel && it !in subclassOnlySpells }

    /** Spells granted by a subclass are never offered as normal picks. */
    private val subclassOnlySpells get() = if (charClass == CharClass.CLERIC) setOf(Spell.THUNDERWAVE, Spell.CALL_LIGHTNING) else emptySet()

    fun learnSpell(spell: Spell): Boolean {
        if (pendingSpells <= 0 || spell !in learnableSpells()) return false
        knownSpells += spell
        pendingSpells--
        return true
    }

    /**
     * Ability score improvement: spend pending points (+2 to one ability or +1 to two), max 20.
     * Keeps current damage when CON (and so max HP) rises.
     */
    fun improveAbilities(increases: Map<Ability, Int>): Boolean {
        val total = increases.values.sum()
        if (total <= 0 || total > pendingAbilityPoints || increases.values.any { it < 0 }) return false
        if (increases.any { (a, v) -> baseAbilities[a] + v > 20 }) return false
        val oldMax = maxHp
        baseAbilities = baseAbilities.withBonuses(increases)
        pendingAbilityPoints -= total
        if (hp > 0) hp = (hp + maxHp - oldMax).coerceIn(1, maxHp)
        return true
    }

    /** Equips [item], returning whatever it replaced. Keeps current damage when max HP changes. */
    fun equip(item: Item): Item? {
        val oldMax = maxHp
        val previous = equipment.put(item.slot, item)
        adjustHpAfter(oldMax)
        return previous
    }

    fun unequip(slot: ItemSlot): Item? {
        val oldMax = maxHp
        val item = equipment.remove(slot)
        adjustHpAfter(oldMax)
        return item
    }

    private fun adjustHpAfter(oldMax: Int) {
        if (hp > 0) hp = (hp + maxHp - oldMax).coerceIn(1, maxHp)
    }

    /** Adds XP and returns a message for each level gained. */
    fun gainXp(amount: Int, dice: Dice): List<String> {
        xp += amount
        val messages = mutableListOf<String>()
        while (level < MAX_LEVEL && xp >= XP_TABLE[level]) {
            val oldMax = maxHp
            level++
            val gain = maxHp - oldMax
            if (hp > 0) hp += gain
            hitDice++
            if (charClass.isCaster) {
                val newMax = SpellSlots.forLevel(level)
                val oldSlots = maxSpellSlots
                spellSlots = IntArray(newMax.size) { i ->
                    (spellSlots.getOrElse(i) { 0 }) + newMax[i] - oldSlots.getOrElse(i) { 0 }
                }
                maxSpellSlots = newMax
            }
            if (isAbilityImprovementLevel(level)) pendingAbilityPoints += 2
            if (charClass.isCaster && learnableSpells().size > pendingSpells) pendingSpells++
            grantSubclassSpells()
            val news = listOf("+$gain max HP") + levelGains(level).map { it.substringBefore(':') }
            messages += "$name reaches level $level! (${news.joinToString(", ")})"
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

    /** Brought back by Revivify. */
    fun revive() {
        dead = false
        hp = 1
        deathSuccesses = 0
        deathFailures = 0
        stable = false
    }

    fun longRest() {
        resourcesUsed.clear()
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
            val roll = dice.roll(charClass.hitDie) * if (hasWoundClosure) 2 else 1
            healed += heal(maxOf(1, roll + conMod))
        }
        secondWindAvailable = true
        actionSurgeAvailable = true
        resourcesUsed.keys.removeAll { it.shortRest }
        return healed
    }

    // --- Saving --------------------------------------------------------------

    fun toSave(): Map<String, Any?> = linkedMapOf(
        "name" to name, "race" to race.name, "class" to charClass.name, "gender" to gender.name,
        "abilities" to Ability.entries.map { baseAbilities[it] },
        "level" to level, "xp" to xp, "hp" to hp,
        "hitDice" to hitDice, "slots" to spellSlots, "maxSlots" to maxSpellSlots,
        "spells" to knownSpells.map { it.name },
        "pendingAbilityPoints" to pendingAbilityPoints, "pendingSpells" to pendingSpells,
        "subclass" to subclass?.name,
        "resourcesUsed" to resourcesUsed.mapKeys { it.key.name },
        "readied" to listOf(smiteReady, envenomed, wrathReady, overchannelReady),
        "equipment" to equipment.values.map { it.toSave() },
        "potions" to potions, "gold" to gold,
        "secondWind" to secondWindAvailable, "actionSurge" to actionSurgeAvailable,
        "reaction" to reactionAvailable, "blessed" to blessed,
        "deathSuccesses" to deathSuccesses, "deathFailures" to deathFailures,
        "stable" to stable, "dead" to dead,
        "x" to x, "y" to y, "dodging" to dodging, "marked" to marked,
    )

    companion object {
        const val MAX_LEVEL = 10
        /** A gentler curve than 5e's: roughly a level every half to two-thirds of a dungeon floor. */
        val XP_TABLE = intArrayOf(0, 80, 250, 550, 1000, 1600, 2350, 3250, 4300, 5500)
        val ASI_LEVELS = setOf(4, 8)
        const val SUBCLASS_LEVEL = 3

        /** Rolled scores are assigned to abilities by class priority, then racial bonuses applied. */
        fun create(name: String, race: Race, charClass: CharClass, rolled: List<Int>, gender: Gender = Gender.MALE): Hero =
            Hero(name, race, charClass, AbilityScores.assign(rolled, charClass.abilityPriority).withBonuses(race.bonuses), gender)

        /** [baseScores] in STR, DEX, CON, INT, WIS, CHA order, before racial bonuses. */
        fun withScores(name: String, race: Race, charClass: CharClass, baseScores: List<Int>, gender: Gender = Gender.MALE): Hero =
            Hero(name, race, charClass, AbilityScores.of(*baseScores.toIntArray()).withBonuses(race.bonuses), gender)

        /** Rebuilds a hero from [toSave] output; version 1 saves had +N bonuses instead of items. */
        fun fromSave(h: JsonObj, version: Int): Hero {
            val hero = Hero(h.str("name"), Race.valueOf(h.str("race")), CharClass.valueOf(h.str("class")),
                AbilityScores.of(*h.ints("abilities")), h.strOrNull("gender")?.let { Gender.valueOf(it) } ?: Gender.MALE)
            hero.level = h.int("level")
            hero.xp = h.int("xp")
            hero.spellSlots = h.ints("slots")
            hero.maxSpellSlots = h.ints("maxSlots")
            if (version >= 2) {
                hero.knownSpells.clear()
                h.list("spells").forEach { hero.knownSpells += Spell.valueOf(it as String) }
                hero.pendingAbilityPoints = h.int("pendingAbilityPoints")
                hero.pendingSpells = h.int("pendingSpells")
                h.list("equipment").forEach { hero.equipment[Item.fromSave(it).slot] = Item.fromSave(it) }
                hero.reactionAvailable = h.bool("reaction")
                hero.blessed = h.bool("blessed")
                h.strOrNull("subclass")?.let { hero.subclass = Subclass.valueOf(it) }
                if (h.has("resourcesUsed")) h.obj("resourcesUsed").map.forEach { (k, v) ->
                    hero.resourcesUsed[Resource.valueOf(k)] = (v as Number).toInt()
                }
                if (h.has("readied")) h.list("readied").map { it as Boolean }.let {
                    hero.smiteReady = it[0]; hero.envenomed = it[1]; hero.wrathReady = it[2]; hero.overchannelReady = it[3]
                }
            } else {
                h.int("weaponBonus").takeIf { it > 0 }?.let { hero.equipment[ItemSlot.WEAPON] = Item(ItemType.MAGIC_WEAPON, it) }
                h.int("armorBonus").takeIf { it > 0 }?.let { hero.equipment[ItemSlot.ARMOR] = Item(ItemType.MAGIC_ARMOR, it) }
            }
            hero.hp = h.int("hp")
            hero.hitDice = h.int("hitDice")
            hero.potions = h.int("potions")
            hero.gold = h.int("gold")
            hero.secondWindAvailable = h.bool("secondWind")
            hero.actionSurgeAvailable = h.bool("actionSurge")
            hero.deathSuccesses = h.int("deathSuccesses")
            hero.deathFailures = h.int("deathFailures")
            hero.stable = h.bool("stable")
            hero.dead = h.bool("dead")
            hero.moveTo(h.int("x"), h.int("y"))
            hero.dodging = h.bool("dodging")
            hero.marked = h.bool("marked")
            return hero
        }
    }
}
