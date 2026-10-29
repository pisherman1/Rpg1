package com.rpg1.core

abstract class Creature(val name: String) {
    var x = 0
    var y = 0
    abstract val abilities: AbilityScores
    abstract var hp: Int
    abstract val maxHp: Int
    abstract val armorClass: Int
    /** Squares per turn. */
    abstract val speed: Int

    /** Dodge action: attacks against this creature have disadvantage until its next turn. */
    var dodging = false
    /** Guiding Bolt: the next attack roll against this creature has advantage. */
    var marked = false

    abstract fun saveBonus(ability: Ability): Int

    open val initiativeBonus: Int get() = abilities.mod(Ability.DEX)

    fun distanceTo(other: Creature) = chebyshev(x, y, other.x, other.y)

    fun isAdjacentTo(other: Creature) = distanceTo(other) == 1

    fun moveTo(nx: Int, ny: Int) {
        x = nx
        y = ny
    }
}

/** Grid distance where a diagonal step counts as one square (5e default). */
fun chebyshev(x1: Int, y1: Int, x2: Int, y2: Int) = maxOf(kotlin.math.abs(x1 - x2), kotlin.math.abs(y1 - y2))
