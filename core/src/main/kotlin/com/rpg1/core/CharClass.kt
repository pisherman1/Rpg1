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
    val spells: List<Spell>,
    val features: String,
) {
    FIGHTER(
        "Fighter", 10, listOf(STR, CON, DEX, WIS, CHA, INT), setOf(STR, CON),
        Weapons.LONGSWORD, Weapons.LIGHT_CROSSBOW, Armors.CHAIN_MAIL_AND_SHIELD,
        null, emptyList(),
        "Second Wind (heal 1d10+lvl), Action Surge at 2nd level",
    ),
    ROGUE(
        "Rogue", 8, listOf(DEX, CON, WIS, INT, CHA, STR), setOf(DEX, INT),
        Weapons.SHORTSWORD, Weapons.SHORTBOW, Armors.LEATHER,
        null, emptyList(),
        "Sneak Attack, Aim (advantage), Cunning Action at 2nd level",
    ),
    WIZARD(
        "Wizard", 6, listOf(INT, CON, DEX, WIS, CHA, STR), setOf(INT, WIS),
        Weapons.QUARTERSTAFF, null, Armors.MAGE_ARMOR,
        INT, listOf(Spell.FIRE_BOLT, Spell.MAGIC_MISSILE, Spell.BURNING_HANDS),
        "Fire Bolt, Magic Missile, Burning Hands",
    ),
    CLERIC(
        "Cleric", 8, listOf(WIS, CON, STR, DEX, CHA, INT), setOf(WIS, CHA),
        Weapons.MACE, null, Armors.SCALE_MAIL_AND_SHIELD,
        WIS, listOf(Spell.SACRED_FLAME, Spell.GUIDING_BOLT, Spell.CURE_WOUNDS),
        "Sacred Flame, Guiding Bolt, Cure Wounds",
    );

    val isCaster get() = spellAbility != null
}
