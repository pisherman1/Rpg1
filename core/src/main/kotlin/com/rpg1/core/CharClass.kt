package com.rpg1.core

import com.rpg1.core.Ability.*

enum class CharClass(
    val displayName: String,
    val hitDie: Int,
    /** Rolled scores are assigned to abilities in this order, best first. */
    val abilityPriority: List<Ability>,
    val savingThrows: Set<Ability>,
    val meleeWeapon: Weapon,
    val rangedWeapon: Weapon?,
    val armor: Armor,
    val spellAbility: Ability?,
    /** Spells known at 1st level. */
    val startingSpells: List<Spell>,
    /** Every spell the class can learn. */
    val spellList: List<Spell>,
    val features: String,
) {
    FIGHTER(
        "Fighter", 10, listOf(STR, CON, DEX, WIS, CHA, INT), setOf(STR, CON),
        Weapons.LONGSWORD, Weapons.LIGHT_CROSSBOW, Armors.CHAIN_MAIL_AND_SHIELD,
        null, emptyList(), emptyList(),
        "Second Wind, Action Surge (2nd), Extra Attack (5th)",
    ),
    ROGUE(
        "Rogue", 8, listOf(DEX, CON, WIS, INT, CHA, STR), setOf(DEX, INT),
        Weapons.SHORTSWORD, Weapons.SHORTBOW, Armors.LEATHER,
        null, emptyList(), emptyList(),
        "Sneak Attack, Aim, Cunning Action (2nd), Uncanny Dodge (5th)",
    ),
    WIZARD(
        "Wizard", 6, listOf(INT, CON, DEX, WIS, CHA, STR), setOf(INT, WIS),
        Weapons.QUARTERSTAFF, null, Armors.MAGE_ARMOR,
        INT, listOf(Spell.FIRE_BOLT, Spell.MAGIC_MISSILE, Spell.BURNING_HANDS),
        listOf(Spell.FIRE_BOLT, Spell.RAY_OF_FROST, Spell.MAGIC_MISSILE, Spell.BURNING_HANDS, Spell.SLEEP,
            Spell.SCORCHING_RAY, Spell.SHATTER, Spell.FIREBALL),
        "Arcane spells; learns a new spell every level",
    ),
    CLERIC(
        "Cleric", 8, listOf(WIS, CON, STR, DEX, CHA, INT), setOf(WIS, CHA),
        Weapons.MACE, null, Armors.SCALE_MAIL_AND_SHIELD,
        WIS, listOf(Spell.SACRED_FLAME, Spell.GUIDING_BOLT, Spell.CURE_WOUNDS),
        listOf(Spell.SACRED_FLAME, Spell.TOLL_THE_DEAD, Spell.GUIDING_BOLT, Spell.CURE_WOUNDS, Spell.HEALING_WORD,
            Spell.BLESS, Spell.PRAYER_OF_HEALING, Spell.MASS_HEALING_WORD, Spell.REVIVIFY),
        "Divine spells; learns a new spell every level",
    );

    val isCaster get() = spellAbility != null
}
