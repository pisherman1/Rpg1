package com.rpg1.core.audio

/** Every sound effect; synthesised by the audio module, triggered by the game and renderer. */
enum class SoundId {
    CLICK, STEP, HIT, CRIT, MISS, BOW, FIRE_CAST, EXPLOSION, MAGIC, ZAP, THUNDER, HOLY, NECRO,
    HEAL, POTION, COIN, CHEST, LOOT, LEVEL_UP, MONSTER_DIE, HERO_DOWN, HERO_DIE, DESCEND,
    COMBAT_START, VICTORY, SLEEP, CHALLENGE, MARK, RAISE, REST,
}

/** Music tracks; [loop] tracks repeat seamlessly. */
enum class TrackId(val loop: Boolean) {
    TITLE(true), EXPLORE(true), COMBAT(true), DEFEAT(false),
}
