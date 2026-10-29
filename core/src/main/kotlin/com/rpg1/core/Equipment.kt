package com.rpg1.core

data class Weapon(
    val name: String,
    val damage: DiceExpr,
    val damageType: String,
    val finesse: Boolean = false,
    val ranged: Boolean = false,
    /** Range in squares; 1 means melee reach. */
    val range: Int = 1,
)

data class Armor(val name: String, val base: Int, val maxDexBonus: Int?, val shield: Int = 0) {
    fun armorClass(dexMod: Int): Int = base + (maxDexBonus?.let { minOf(it, dexMod) } ?: dexMod) + shield
}

object Weapons {
    val LONGSWORD = Weapon("Longsword", d("1d8"), "slashing")
    val LIGHT_CROSSBOW = Weapon("Light Crossbow", d("1d8"), "piercing", ranged = true, range = 8)
    val SHORTSWORD = Weapon("Shortsword", d("1d6"), "piercing", finesse = true)
    val SHORTBOW = Weapon("Shortbow", d("1d6"), "piercing", ranged = true, range = 10)
    val QUARTERSTAFF = Weapon("Quarterstaff", d("1d6"), "bludgeoning")
    val MACE = Weapon("Mace", d("1d6"), "bludgeoning")
}

object Armors {
    val CHAIN_MAIL_AND_SHIELD = Armor("Chain Mail & Shield", 16, 0, shield = 2)
    val LEATHER = Armor("Leather Armor", 11, null)
    val MAGE_ARMOR = Armor("Mage Armor", 13, null)
    val SCALE_MAIL_AND_SHIELD = Armor("Scale Mail & Shield", 14, 2, shield = 2)
}
