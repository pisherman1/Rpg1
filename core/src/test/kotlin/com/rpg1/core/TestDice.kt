package com.rpg1.core

/** Returns the scripted values in order (each clamped to the die size), then falls back to 1s. */
class ScriptedDice(vararg values: Int) : Dice() {
    private val queue = ArrayDeque(values.toList())

    override fun roll(sides: Int): Int = (queue.removeFirstOrNull() ?: 1).coerceIn(1, sides)
}
