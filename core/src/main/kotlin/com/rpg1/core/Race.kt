package com.rpg1.core

import com.rpg1.core.Ability.*

/** Playable races. Speed is in 5-foot squares (6 squares = 30 ft). */
enum class Race(
    val displayName: String,
    val bonuses: Map<Ability, Int>,
    val speed: Int,
    val sightRadius: Int,
    val trait: String,
) {
    HUMAN("Human", Ability.entries.associateWith { 1 }, 6, 8, "Versatile: +1 to every ability"),
    DWARF("Dwarf", mapOf(CON to 2, WIS to 1), 5, 8, "Dwarven Toughness: +1 HP per level"),
    ELF("Elf", mapOf(DEX to 2, INT to 1), 7, 10, "Keen Senses & Fleet of Foot: see farther, 35 ft speed"),
    HALFLING("Halfling", mapOf(DEX to 2, CHA to 1), 5, 8, "Lucky: reroll natural 1s on d20s");

    val bonusText: String
        get() = if (this == HUMAN) "+1 all" else bonuses.entries.joinToString(" ") { "${it.key.name} +${it.value}" }
}
