package com.rpg1.app

import android.graphics.Bitmap
import com.rpg1.core.CharClass
import com.rpg1.core.MonsterType
import com.rpg1.core.Race
import com.rpg1.core.art.Palette
import com.rpg1.core.art.Sprite
import com.rpg1.core.art.Sprites

/** Turns the text sprites into bitmaps once, including race variants of each class. */
object SpriteCache {
    private val cache = HashMap<String, Bitmap>()

    private fun bitmap(sprite: Sprite, key: String = sprite.name, edit: (IntArray) -> Unit = {}): Bitmap =
        cache.getOrPut(key) {
            val px = sprite.pixels()
            edit(px)
            Bitmap.createBitmap(px, sprite.width, sprite.height, Bitmap.Config.ARGB_8888)
        }

    fun get(sprite: Sprite) = bitmap(sprite)

    private fun classSprite(cls: CharClass) = when (cls) {
        CharClass.FIGHTER -> Sprites.FIGHTER
        CharClass.ROGUE -> Sprites.ROGUE
        CharClass.WIZARD -> Sprites.WIZARD
        CharClass.CLERIC -> Sprites.CLERIC
    }

    /** Heroes share a face position, so dwarves get a beard and elves get pointed ears. */
    fun hero(race: Race, cls: CharClass): Bitmap = bitmap(classSprite(cls), "${cls.name}_${race.name}") { px ->
        val skin = Palette.colors.getValue('s')
        val outline = Palette.colors.getValue('k')
        fun set(x: Int, y: Int, c: Int) { px[y * 16 + x] = c }
        when (race) {
            Race.DWARF -> {
                val beard = 0xFFB0602A.toInt()
                set(5, 6, beard); set(10, 6, beard)
                for (x in 5..10) set(x, 7, beard)
                for (x in 6..9) set(x, 8, beard)
                set(7, 9, beard); set(8, 9, beard)
            }
            Race.ELF -> {
                set(3, 4, outline); set(12, 4, outline)
                set(3, 5, skin); set(12, 5, skin)
                set(2, 4, outline); set(13, 4, outline)
            }
            else -> {}
        }
    }

    /** Halflings (and dwarves, a little) are drawn shorter. */
    fun heroScale(race: Race) = when (race) {
        Race.HALFLING -> 0.78f
        Race.DWARF -> 0.9f
        else -> 1f
    }

    fun monster(type: MonsterType): Bitmap = get(
        when (type) {
            MonsterType.GIANT_RAT -> Sprites.GIANT_RAT
            MonsterType.KOBOLD -> Sprites.KOBOLD
            MonsterType.GOBLIN -> Sprites.GOBLIN
            MonsterType.SKELETON -> Sprites.SKELETON
            MonsterType.ZOMBIE -> Sprites.ZOMBIE
            MonsterType.ORC -> Sprites.ORC
            MonsterType.GNOLL -> Sprites.GNOLL
            MonsterType.GHOUL -> Sprites.GHOUL
            MonsterType.BUGBEAR -> Sprites.BUGBEAR
            MonsterType.OGRE -> Sprites.OGRE
        },
    )
}
