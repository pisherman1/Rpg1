package com.rpg1.core

import kotlin.random.Random

/** A dice expression such as `2d6+3`. */
data class DiceExpr(val count: Int, val sides: Int, val bonus: Int = 0) {
    operator fun plus(extraBonus: Int) = copy(bonus = bonus + extraBonus)

    fun withCount(newCount: Int) = copy(count = newCount)

    val average: Double get() = count * (sides + 1) / 2.0 + bonus

    override fun toString(): String = buildString {
        append("${count}d$sides")
        if (bonus > 0) append("+$bonus")
        if (bonus < 0) append(bonus)
    }

    companion object {
        private val PATTERN = Regex("""(\d+)d(\d+)([+-]\d+)?""")

        fun parse(text: String): DiceExpr {
            val m = PATTERN.matchEntire(text.replace(" ", ""))
                ?: throw IllegalArgumentException("Not a dice expression: $text")
            return DiceExpr(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(),
            )
        }
    }
}

/** Shorthand: `d("2d6+3")`. */
fun d(text: String) = DiceExpr.parse(text)

enum class RollMode {
    NORMAL, ADVANTAGE, DISADVANTAGE;

    companion object {
        /** Advantage and disadvantage cancel out, regardless of how many sources of each. */
        fun of(advantage: Boolean, disadvantage: Boolean): RollMode = when {
            advantage && !disadvantage -> ADVANTAGE
            disadvantage && !advantage -> DISADVANTAGE
            else -> NORMAL
        }
    }
}

/** Result of a d20 roll: every die thrown plus the one that counts. */
data class D20Roll(val rolls: List<Int>, val natural: Int, val mode: RollMode) {
    val isCritical get() = natural == 20
    val isFumble get() = natural == 1

    /** e.g. "d20(4,17)" for advantage, or "d20 12" for a straight roll. */
    fun describe(): String =
        if (rolls.size == 1) "d20 $natural" else "d20(${rolls.joinToString(",")})"
}

open class Dice(private val rng: Random = Random.Default) {
    open fun roll(sides: Int): Int = rng.nextInt(1, sides + 1)

    fun roll(count: Int, sides: Int): Int = (0 until count).sumOf { roll(sides) }

    /** Rolls [expr]; a critical hit doubles the number of dice, not the flat bonus. */
    fun roll(expr: DiceExpr, critical: Boolean = false): Int {
        val count = if (critical) expr.count * 2 else expr.count
        return roll(count, expr.sides) + expr.bonus
    }

    /**
     * Rolls a d20. With [lucky] (halfling trait) a natural 1 is rerolled once,
     * and the new roll must be used.
     */
    fun d20(mode: RollMode = RollMode.NORMAL, lucky: Boolean = false): D20Roll {
        fun one(): Int {
            val r = roll(20)
            return if (lucky && r == 1) roll(20) else r
        }
        return when (mode) {
            RollMode.NORMAL -> one().let { D20Roll(listOf(it), it, mode) }
            RollMode.ADVANTAGE -> listOf(one(), one()).let { D20Roll(it, it.max(), mode) }
            RollMode.DISADVANTAGE -> listOf(one(), one()).let { D20Roll(it, it.min(), mode) }
        }
    }

    /** Classic ability score generation: roll 4d6, drop the lowest die. */
    fun abilityScore(): Int = List(4) { roll(6) }.sorted().drop(1).sum()

    fun chance(numerator: Int, denominator: Int): Boolean = roll(denominator) <= numerator

    fun <T> pick(items: List<T>): T = items[roll(items.size) - 1]
}
