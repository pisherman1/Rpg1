package com.rpg1.core

enum class Ability(val label: String) {
    STR("Strength"), DEX("Dexterity"), CON("Constitution"),
    INT("Intelligence"), WIS("Wisdom"), CHA("Charisma"),
}

fun abilityModifier(score: Int): Int = Math.floorDiv(score - 10, 2)

fun formatModifier(mod: Int): String = if (mod >= 0) "+$mod" else "$mod"

class AbilityScores(scores: Map<Ability, Int>) {
    private val scores: Map<Ability, Int> = Ability.entries.associateWith { scores[it] ?: 10 }

    operator fun get(ability: Ability): Int = scores.getValue(ability)

    fun mod(ability: Ability): Int = abilityModifier(get(ability))

    fun withBonuses(bonuses: Map<Ability, Int>) =
        AbilityScores(scores.mapValues { (a, v) -> minOf(20, v + (bonuses[a] ?: 0)) })

    fun toMap(): Map<Ability, Int> = scores

    companion object {
        /** STR, DEX, CON, INT, WIS, CHA order. */
        fun of(vararg values: Int): AbilityScores {
            require(values.size == 6)
            return AbilityScores(Ability.entries.zip(values.toList()).toMap())
        }

        /** Assigns rolled [values] to abilities, highest roll to the first ability in [priority]. */
        fun assign(values: List<Int>, priority: List<Ability>): AbilityScores {
            require(values.size == 6 && priority.toSet().size == 6)
            return AbilityScores(priority.zip(values.sortedDescending()).toMap())
        }
    }
}
