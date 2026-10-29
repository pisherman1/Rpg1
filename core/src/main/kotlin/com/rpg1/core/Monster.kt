package com.rpg1.core

data class MonsterAttack(
    val name: String,
    val toHit: Int,
    val damage: DiceExpr,
    val damageType: String,
    val range: Int = 1,
) {
    val ranged get() = range > 1
}

/** Stat blocks adapted from the 5e System Reference Document. Speed in squares. */
enum class MonsterType(
    val displayName: String,
    val challenge: String,
    val cr: Double,
    val xp: Int,
    val armorClass: Int,
    val hitDice: DiceExpr,
    val speed: Int,
    val scores: IntArray,
    val attacks: List<MonsterAttack>,
    val passivePerception: Int,
) {
    GIANT_RAT(
        "Giant Rat", "1/8", 0.125, 25, 12, d("2d6"), 6, intArrayOf(7, 15, 11, 2, 10, 4),
        listOf(MonsterAttack("Bite", 4, d("1d4+2"), "piercing")), 10,
    ),
    KOBOLD(
        "Kobold", "1/8", 0.125, 25, 12, d("2d6-2"), 6, intArrayOf(7, 15, 9, 8, 7, 8),
        listOf(
            MonsterAttack("Dagger", 4, d("1d4+2"), "piercing"),
            MonsterAttack("Sling", 4, d("1d4+2"), "bludgeoning", range = 6),
        ), 8,
    ),
    GOBLIN(
        "Goblin", "1/4", 0.25, 50, 15, d("2d6"), 6, intArrayOf(8, 14, 10, 10, 8, 8),
        listOf(
            MonsterAttack("Scimitar", 4, d("1d6+2"), "slashing"),
            MonsterAttack("Shortbow", 4, d("1d6+2"), "piercing", range = 8),
        ), 9,
    ),
    SKELETON(
        "Skeleton", "1/4", 0.25, 50, 13, d("2d8+4"), 6, intArrayOf(10, 14, 15, 6, 8, 5),
        listOf(
            MonsterAttack("Shortsword", 4, d("1d6+2"), "piercing"),
            MonsterAttack("Shortbow", 4, d("1d6+2"), "piercing", range = 8),
        ), 9,
    ),
    ZOMBIE(
        "Zombie", "1/4", 0.25, 50, 8, d("3d8+9"), 4, intArrayOf(13, 6, 16, 3, 6, 5),
        listOf(MonsterAttack("Slam", 3, d("1d6+1"), "bludgeoning")), 8,
    ),
    ORC(
        "Orc", "1/2", 0.5, 100, 13, d("2d8+6"), 6, intArrayOf(16, 12, 16, 7, 11, 10),
        listOf(
            MonsterAttack("Greataxe", 5, d("1d12+3"), "slashing"),
            MonsterAttack("Javelin", 5, d("1d6+3"), "piercing", range = 6),
        ), 10,
    ),
    GNOLL(
        "Gnoll", "1/2", 0.5, 100, 15, d("5d8"), 6, intArrayOf(14, 12, 11, 6, 10, 7),
        listOf(
            MonsterAttack("Spear", 4, d("1d6+2"), "piercing"),
            MonsterAttack("Longbow", 3, d("1d8+1"), "piercing", range = 12),
        ), 10,
    ),
    GHOUL(
        "Ghoul", "1", 1.0, 200, 12, d("5d8"), 6, intArrayOf(13, 15, 10, 7, 10, 6),
        listOf(MonsterAttack("Claws", 4, d("2d4+2"), "slashing")), 10,
    ),
    BUGBEAR(
        "Bugbear", "1", 1.0, 200, 16, d("5d8+5"), 6, intArrayOf(15, 14, 13, 8, 11, 9),
        listOf(MonsterAttack("Morningstar", 4, d("2d8+2"), "piercing")), 10,
    ),
    OGRE(
        "Ogre", "2", 2.0, 450, 11, d("7d10+21"), 8, intArrayOf(19, 8, 16, 5, 7, 7),
        listOf(
            MonsterAttack("Greatclub", 6, d("2d8+4"), "bludgeoning"),
            MonsterAttack("Javelin", 6, d("2d6+4"), "piercing", range = 6),
        ), 8,
    );

    val melee: MonsterAttack get() = attacks.first { !it.ranged }
    val rangedAttack: MonsterAttack? get() = attacks.firstOrNull { it.ranged }
    val isUndead get() = this == SKELETON || this == ZOMBIE || this == GHOUL
}

class Monster(val type: MonsterType, val baseMaxHp: Int) : Creature(type.displayName) {
    /** A fresh monster with hit points rolled from its hit dice. */
    constructor(type: MonsterType, dice: Dice) : this(type, maxOf(1, dice.roll(type.hitDice)))

    override val abilities = AbilityScores.of(*type.scores)
    override var hp: Int = baseMaxHp
    override val armorClass get() = type.armorClass
    override val speed get() = type.speed

    /** Has noticed the hero; aware monsters hunt the hero even outside combat. */
    var aware = false
    /** Surprised creatures lose their first turn of combat and can't react until it ends. */
    var surprised = false
    var reactionAvailable = true
    /** Sleep spell: loses its turns until damaged; attacks against it have advantage. */
    var asleep = false
    /** Fights for the party (a necromancer's skeleton), raised by [master]. */
    var ally = false
    var master: Hero? = null
    /** Has taken a turn in the current fight (for Assassinate). */
    var acted = false
    /** Knight who challenged this monster, and ranger who marked it. */
    var challenger: Hero? = null
    var huntedBy: Hero? = null
    /** Extra damage on its attacks (Undead Thralls). */
    var bonusDamage = 0

    override val maxHp: Int get() = baseMaxHp + extraHp
    /** Extra hit points granted after creation (Undead Thralls). */
    var extraHp = 0

    val isDead get() = hp <= 0

    override fun saveBonus(ability: Ability) = abilities.mod(ability)
}
