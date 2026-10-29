package com.rpg1.core

/** ALLY spells touch: self or an adjacent party member (anyone in the party outside combat). */
enum class SpellTarget { ENEMY, ALLY, ADJACENT_AREA }

enum class Spell(
    val displayName: String,
    /** 0 = cantrip. */
    val level: Int,
    val target: SpellTarget,
    val range: Int,
    val dice: DiceExpr,
    val damageType: String,
    val saveAbility: Ability? = null,
    val description: String,
) {
    FIRE_BOLT(
        "Fire Bolt", 0, SpellTarget.ENEMY, 10, d("1d10"), "fire",
        description = "Ranged spell attack, 1d10 fire. Cantrip.",
    ),
    MAGIC_MISSILE(
        "Magic Missile", 1, SpellTarget.ENEMY, 10, d("1d4+1"), "force",
        description = "3 darts of 1d4+1 force that never miss. +1 dart per slot level.",
    ),
    BURNING_HANDS(
        "Burning Hands", 1, SpellTarget.ADJACENT_AREA, 1, d("3d6"), "fire", Ability.DEX,
        description = "All adjacent foes: DEX save or 3d6 fire (half on success).",
    ),
    SACRED_FLAME(
        "Sacred Flame", 0, SpellTarget.ENEMY, 8, d("1d8"), "radiant", Ability.DEX,
        description = "DEX save or 1d8 radiant. Cantrip.",
    ),
    CURE_WOUNDS(
        "Cure Wounds", 1, SpellTarget.ALLY, 1, d("1d8"), "healing",
        description = "Touch: heal 1d8 + WIS. +1d8 per slot level.",
    ),
    GUIDING_BOLT(
        "Guiding Bolt", 1, SpellTarget.ENEMY, 10, d("4d6"), "radiant",
        description = "Ranged spell attack, 4d6 radiant; next attack on target has advantage.",
    );

    val isCantrip get() = level == 0

    /** Shown on buttons: short enough to fit. */
    val shortName: String
        get() = when (this) {
            MAGIC_MISSILE -> "Missile"
            BURNING_HANDS -> "Burn Hands"
            SACRED_FLAME -> "Sacred Fl."
            GUIDING_BOLT -> "Guide Bolt"
            else -> displayName
        }
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
