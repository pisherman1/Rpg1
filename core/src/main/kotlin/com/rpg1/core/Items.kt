package com.rpg1.core

enum class ItemSlot(val label: String) {
    WEAPON("Weapon"), ARMOR("Armor"), FOCUS("Focus"), RING("Ring"), CLOAK("Cloak"),
    NECK("Neck"), HANDS("Hands"), HEAD("Head"), FEET("Feet"),
}

enum class Rarity(val label: String, val color: Int) {
    UNCOMMON("Uncommon", 0xFF5ED35E.toInt()),
    RARE("Rare", 0xFF5AA0FF.toInt()),
    VERY_RARE("Very rare", 0xFFC070FF.toInt()),
}

/** Magic items, adapted from the 5e System Reference Document. */
enum class ItemType(
    val displayName: String,
    val slot: ItemSlot,
    val description: String,
    /** Only spellcasters benefit. */
    val casterOnly: Boolean = false,
    /** Fixed rarity, or null when it depends on the +N bonus. */
    val fixedRarity: Rarity? = null,
) {
    MAGIC_WEAPON("Weapon", ItemSlot.WEAPON, "+N to attack and damage rolls with your weapons."),
    MAGIC_ARMOR("Armor", ItemSlot.ARMOR, "+N to Armor Class."),
    WAND_OF_THE_WAR_MAGE("Wand of the War Mage", ItemSlot.FOCUS, "+N to spell attack rolls and spell save DC.", casterOnly = true),
    RING_OF_PROTECTION("Ring of Protection", ItemSlot.RING, "+1 to Armor Class and saving throws.", fixedRarity = Rarity.UNCOMMON),
    CLOAK_OF_PROTECTION("Cloak of Protection", ItemSlot.CLOAK, "+1 to Armor Class and saving throws.", fixedRarity = Rarity.UNCOMMON),
    AMULET_OF_HEALTH("Amulet of Health", ItemSlot.NECK, "Your Constitution score is 19.", fixedRarity = Rarity.RARE),
    PERIAPT_OF_WOUND_CLOSURE("Periapt of Wound Closure", ItemSlot.NECK,
        "You stabilise automatically at 0 HP, and hit dice heal double.", fixedRarity = Rarity.UNCOMMON),
    GAUNTLETS_OF_OGRE_POWER("Gauntlets of Ogre Power", ItemSlot.HANDS, "Your Strength score is 19.", fixedRarity = Rarity.UNCOMMON),
    HEADBAND_OF_INTELLECT("Headband of Intellect", ItemSlot.HEAD, "Your Intelligence score is 19.", fixedRarity = Rarity.UNCOMMON),
    BOOTS_OF_STRIDING("Boots of Striding", ItemSlot.FEET, "+5 ft speed (one extra square of movement).", fixedRarity = Rarity.UNCOMMON);

    val hasBonus get() = fixedRarity == null
}

/** A specific item; [bonus] is the +N for weapons, armor and wands. */
data class Item(val type: ItemType, val bonus: Int = 0) {
    val slot get() = type.slot

    val rarity: Rarity
        get() = type.fixedRarity ?: when (bonus) {
            1 -> Rarity.UNCOMMON
            2 -> Rarity.RARE
            else -> Rarity.VERY_RARE
        }

    val name: String get() = if (type.hasBonus) "+$bonus ${type.displayName}" else type.displayName

    /** Name as worn by [hero], e.g. "+1 Longsword". */
    fun nameFor(hero: Hero): String = when (type) {
        ItemType.MAGIC_WEAPON -> "+$bonus ${hero.charClass.meleeWeapon.name}"
        ItemType.MAGIC_ARMOR -> "+$bonus ${hero.charClass.armor.name}"
        else -> name
    }

    val description: String get() = type.description.replace("+N", "+$bonus")

    fun usableBy(hero: Hero) = !type.casterOnly || hero.charClass.isCaster

    /** Ability scores this item sets (5e "your score is 19" items). */
    val setsAbility: Pair<Ability, Int>?
        get() = when (type) {
            ItemType.AMULET_OF_HEALTH -> Ability.CON to 19
            ItemType.GAUNTLETS_OF_OGRE_POWER -> Ability.STR to 19
            ItemType.HEADBAND_OF_INTELLECT -> Ability.INT to 19
            else -> null
        }

    fun toSave(): List<Any> = listOf(type.name, bonus)

    companion object {
        fun fromSave(v: Any?): Item {
            val l = v as List<*>
            return Item(ItemType.valueOf(l[0] as String), (l[1] as Number).toInt())
        }
    }
}

/** Random treasure; deeper levels roll better rarities. */
object Loot {
    private val UNCOMMON = listOf(
        Item(ItemType.MAGIC_WEAPON, 1), Item(ItemType.MAGIC_ARMOR, 1), Item(ItemType.WAND_OF_THE_WAR_MAGE, 1),
        Item(ItemType.RING_OF_PROTECTION), Item(ItemType.CLOAK_OF_PROTECTION), Item(ItemType.PERIAPT_OF_WOUND_CLOSURE),
        Item(ItemType.GAUNTLETS_OF_OGRE_POWER), Item(ItemType.HEADBAND_OF_INTELLECT), Item(ItemType.BOOTS_OF_STRIDING),
    )
    private val RARE = listOf(
        Item(ItemType.MAGIC_WEAPON, 2), Item(ItemType.MAGIC_ARMOR, 2), Item(ItemType.WAND_OF_THE_WAR_MAGE, 2),
        Item(ItemType.AMULET_OF_HEALTH),
    )
    private val VERY_RARE = listOf(
        Item(ItemType.MAGIC_WEAPON, 3), Item(ItemType.MAGIC_ARMOR, 3), Item(ItemType.WAND_OF_THE_WAR_MAGE, 3),
    )

    val all get() = UNCOMMON + RARE + VERY_RARE

    fun randomItem(dice: Dice, depth: Int, boss: Boolean = false): Item {
        val roll = dice.roll(100) + depth * 5 + if (boss) 25 else 0
        return dice.pick(when {
            roll > 125 -> VERY_RARE
            roll > 85 -> RARE
            else -> UNCOMMON
        })
    }
}
