package com.rpg1.core

enum class SpellTarget {
    /** One visible enemy in range. */
    ENEMY,
    /** Every enemy within [Spell.radius] of a target enemy. */
    ENEMY_AREA,
    /** Every enemy next to the caster. */
    ADJACENT_AREA,
    /** One hero: touch in combat when range is 1, anyone outside combat. */
    ALLY,
    /** The whole party (within range in combat). */
    PARTY,
    /** A fallen hero. */
    DEAD_ALLY,
}

enum class Spell(
    val displayName: String,
    /** 0 = cantrip. */
    val level: Int,
    val target: SpellTarget,
    val range: Int,
    val dice: DiceExpr,
    val damageType: String,
    val description: String,
    val saveAbility: Ability? = null,
    /** Extra dice per slot level above the spell's level. */
    val upcastDice: Int = 0,
    val radius: Int = 0,
    val bonusAction: Boolean = false,
    val outOfCombatOnly: Boolean = false,
    val shortName: String = displayName,
) {
    // --- Wizard -------------------------------------------------------------
    FIRE_BOLT("Fire Bolt", 0, SpellTarget.ENEMY, 10, d("1d10"), "fire",
        "Ranged spell attack: 1d10 fire."),
    RAY_OF_FROST("Ray of Frost", 0, SpellTarget.ENEMY, 10, d("1d8"), "cold",
        "Ranged spell attack: 1d8 cold.", shortName = "Frost Ray"),
    MAGIC_MISSILE("Magic Missile", 1, SpellTarget.ENEMY, 10, d("1d4+1"), "force",
        "3 darts of 1d4+1 force that never miss. +1 dart per higher slot.", upcastDice = 1, shortName = "Missile"),
    BURNING_HANDS("Burning Hands", 1, SpellTarget.ADJACENT_AREA, 1, d("3d6"), "fire",
        "Adjacent foes: DEX save or 3d6 fire, half on a save. +1d6 per higher slot.",
        Ability.DEX, upcastDice = 1, shortName = "Burn Hands"),
    THUNDERWAVE("Thunderwave", 1, SpellTarget.ADJACENT_AREA, 1, d("2d8"), "thunder",
        "Adjacent foes: CON save or 2d8 thunder, half on a save. +1d8 per higher slot.",
        Ability.CON, upcastDice = 1, shortName = "Thunderwave"),
    SLEEP("Sleep", 1, SpellTarget.ENEMY_AREA, 10, d("5d8"), "sleep",
        "5d8 HP worth of foes near the target fall asleep, weakest first. +2d8 per higher slot.",
        upcastDice = 2, radius = 2),
    SCORCHING_RAY("Scorching Ray", 2, SpellTarget.ENEMY, 10, d("2d6"), "fire",
        "3 rays, each a spell attack for 2d6 fire. +1 ray per higher slot.", upcastDice = 1, shortName = "Scorch Ray"),
    SHATTER("Shatter", 2, SpellTarget.ENEMY_AREA, 10, d("3d8"), "thunder",
        "Foes around the target: CON save or 3d8 thunder, half on a save. +1d8 per higher slot.",
        Ability.CON, upcastDice = 1, radius = 1),
    FIREBALL("Fireball", 3, SpellTarget.ENEMY_AREA, 12, d("8d6"), "fire",
        "Foes within 10 ft of the target: DEX save or 8d6 fire, half on a save. +1d6 per higher slot.",
        Ability.DEX, upcastDice = 1, radius = 2),

    ACID_ARROW("Acid Arrow", 2, SpellTarget.ENEMY, 12, d("6d4"), "acid",
        "Ranged spell attack: 6d4 acid. +1d4 per higher slot.", upcastDice = 1),
    LIGHTNING_BOLT("Lightning Bolt", 3, SpellTarget.ENEMY_AREA, 12, d("8d6"), "lightning",
        "A bolt through every foe in a line toward the target: DEX save or 8d6 lightning, half on a save. +1d6 per higher slot.",
        Ability.DEX, upcastDice = 1, shortName = "Light. Bolt"),
    ICE_STORM("Ice Storm", 4, SpellTarget.ENEMY_AREA, 12, d("4d10"), "cold",
        "Hail crashes down within 10 ft of the target: DEX save or 4d10 cold, half on a save. +1d10 per higher slot.",
        Ability.DEX, upcastDice = 1, radius = 2),
    CONE_OF_COLD("Cone of Cold", 5, SpellTarget.ENEMY_AREA, 6, d("8d8"), "cold",
        "A blast of frost around the target: CON save or 8d8 cold, half on a save. +1d8 per higher slot.",
        Ability.CON, upcastDice = 1, radius = 2, shortName = "Cone Cold"),
    CALL_LIGHTNING("Call Lightning", 3, SpellTarget.ENEMY_AREA, 12, d("3d10"), "lightning",
        "A bolt strikes the target: foes within 5 ft make a DEX save or take 3d10 lightning, half on a save. +1d10 per higher slot.",
        Ability.DEX, upcastDice = 1, radius = 1, shortName = "Call Light."),

    // --- Cleric -------------------------------------------------------------
    SACRED_FLAME("Sacred Flame", 0, SpellTarget.ENEMY, 8, d("1d8"), "radiant",
        "DEX save or 1d8 radiant.", Ability.DEX, shortName = "Sacred Fl."),
    TOLL_THE_DEAD("Toll the Dead", 0, SpellTarget.ENEMY, 8, d("1d8"), "necrotic",
        "WIS save or 1d8 necrotic (1d12 if it's already hurt).", Ability.WIS, shortName = "Toll Dead"),
    INFLICT_WOUNDS("Inflict Wounds", 1, SpellTarget.ENEMY, 1, d("3d10"), "necrotic",
        "Melee spell attack: 3d10 necrotic. +1d10 per higher slot.", upcastDice = 1, shortName = "Inflict"),
    SPIRIT_GUARDIANS("Spirit Guardians", 3, SpellTarget.ADJACENT_AREA, 2, d("3d8"), "radiant",
        "Spirits strike foes within 10 ft: WIS save or 3d8 radiant, half on a save. +1d8 per higher slot.",
        Ability.WIS, upcastDice = 1, radius = 2, shortName = "Guardians"),
    FLAME_STRIKE("Flame Strike", 5, SpellTarget.ENEMY_AREA, 12, d("8d6"), "fire",
        "A pillar of holy fire around the target: DEX save or 8d6 fire and radiant, half on a save. +1d6 per higher slot.",
        Ability.DEX, upcastDice = 1, radius = 1, shortName = "Flame Strk"),
    MASS_CURE_WOUNDS("Mass Cure Wounds", 5, SpellTarget.PARTY, 6, d("3d8"), "healing",
        "Allies within 30 ft heal 3d8 + WIS. +1d8 per higher slot.", upcastDice = 1, shortName = "Mass Cure"),
    GUIDING_BOLT("Guiding Bolt", 1, SpellTarget.ENEMY, 10, d("4d6"), "radiant",
        "Ranged spell attack: 4d6 radiant; the next attack on the target has advantage. +1d6 per higher slot.",
        upcastDice = 1, shortName = "Guide Bolt"),
    CURE_WOUNDS("Cure Wounds", 1, SpellTarget.ALLY, 1, d("1d8"), "healing",
        "Touch: heal 1d8 + WIS. +1d8 per higher slot.", upcastDice = 1, shortName = "Cure"),
    HEALING_WORD("Healing Word", 1, SpellTarget.ALLY, 6, d("1d4"), "healing",
        "Bonus action, 30 ft: heal 1d4 + WIS. +1d4 per higher slot.", upcastDice = 1, bonusAction = true,
        shortName = "Heal Word"),
    BLESS("Bless", 1, SpellTarget.PARTY, 6, d("1d4"), "bless",
        "Allies within 30 ft add 1d4 to attack rolls until the fight ends."),
    PRAYER_OF_HEALING("Prayer of Healing", 2, SpellTarget.PARTY, 6, d("2d8"), "healing",
        "Out of combat: every hero heals 2d8 + WIS. +1d8 per higher slot.", upcastDice = 1, outOfCombatOnly = true,
        shortName = "Prayer"),
    MASS_HEALING_WORD("Mass Healing Word", 3, SpellTarget.PARTY, 6, d("1d4"), "healing",
        "Bonus action: allies within 30 ft heal 1d4 + WIS. +1d4 per higher slot.", upcastDice = 1, bonusAction = true,
        shortName = "Mass Heal"),
    REVIVIFY("Revivify", 3, SpellTarget.DEAD_ALLY, 0, d("1d1"), "life",
        "Out of combat, costs 300 gold: a fallen hero returns with 1 HP.", outOfCombatOnly = true);

    val isCantrip get() = level == 0
    val isHealing get() = damageType == "healing"
}

/** Spell slots per spell level (index 0 = 1st level) for a full caster, levels 1-10. */
object SpellSlots {
    private val TABLE = listOf(
        intArrayOf(2),
        intArrayOf(3),
        intArrayOf(4, 2),
        intArrayOf(4, 3),
        intArrayOf(4, 3, 2),
        intArrayOf(4, 3, 3),
        intArrayOf(4, 3, 3, 1),
        intArrayOf(4, 3, 3, 2),
        intArrayOf(4, 3, 3, 3, 1),
        intArrayOf(4, 3, 3, 3, 2),
    )

    fun forLevel(level: Int): IntArray = TABLE[(level - 1).coerceIn(0, TABLE.lastIndex)].copyOf()
}
